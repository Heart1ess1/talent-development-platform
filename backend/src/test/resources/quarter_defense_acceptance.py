"""HTTP/MySQL acceptance against a disposable local QA database (stdlib only).

Start the application against the same database before running this file.
The database name MUST start with talent_scoring_qa. Never use production data.
"""
import concurrent.futures, datetime as dt, io, json, subprocess, sys, time, urllib.request, urllib.error, urllib.parse, zipfile
from xml.sax.saxutils import escape

BASE = 'http://127.0.0.1:18086/api/v1'
CONTAINER = 'talent-scoring-qa-1015'
DATABASE = sys.argv[1] if len(sys.argv)>1 else 'talent_scoring_qa2'
assert DATABASE.startswith('talent_scoring_qa') and DATABASE.replace('_','').isalnum()
checks=[]
def sql(statement):
    result=subprocess.run(['docker','exec','-i',CONTAINER,'mysql','--default-character-set=utf8mb4','-uroot','-pqa_local_1015','-N','-B',DATABASE],input=statement,text=True,encoding='utf-8',capture_output=True)
    if result.returncode: raise AssertionError(result.stderr)
    return result.stdout.strip()
def request(method,path,data=None,token=None,expected=200,content_type=None):
    body=data if isinstance(data,bytes) else json.dumps(data,ensure_ascii=False).encode() if data is not None else None
    headers={'Content-Type':content_type or 'application/json'}
    if token:headers['Authorization']='Bearer '+token
    try:
        response=urllib.request.urlopen(urllib.request.Request(BASE+path,body,headers,method=method),timeout=30)
        status=response.status; raw=response.read()
    except urllib.error.HTTPError as error:status=error.code;raw=error.read()
    assert status==expected,(method,path,status,raw.decode(errors='replace')[:500])
    if expected!=200:return None
    envelope=json.loads(raw);assert envelope['code']==0,envelope
    return envelope['data']
def login(user):return request('POST','/auth/login',{'username':user,'password':'12345678'})['token']
def check(name,condition=True):
    assert condition,name
    checks.append(name);print('PASS',name,flush=True)
def month(date):return date.strftime('%Y-%m')
def next_month(date):return (date.replace(day=28)+dt.timedelta(days=4)).replace(day=1)
def end_month(date):return (next_month(date)-dt.timedelta(days=1)).strftime('%Y-%m-%dT23:59:59')

admin=login('admin');tag='QD'+str(time.time_ns())[-10:];no='00'+tag[2:];other_no='01'+tag[2:];username='quarter_'+tag
sql(f"INSERT INTO talent_batch(name) VALUES('{tag}'); INSERT INTO sys_user(username,password_hash,display_name,role,enabled,must_change_password) SELECT '{username}',password_hash,'季度答辩QA员工','EMPLOYEE',true,false FROM sys_user WHERE username='employee'")
batch=int(sql(f"SELECT id FROM talent_batch WHERE name='{tag}'"));mentor=int(sql("SELECT id FROM sys_user WHERE username='mentor'"))
sql(f"INSERT INTO employee(user_id,employee_no,name,batch_id,mentor_user_id) SELECT id,'{no}','季度答辩QA员工',{batch},{mentor} FROM sys_user WHERE username='{username}'; INSERT INTO employee(employee_no,name,batch_id,mentor_user_id) VALUES('{other_no}','季度答辩QA第二人',{batch},{mentor})")
employee=int(sql(f"SELECT id FROM employee WHERE employee_no='{no}'"));other=int(sql(f"SELECT id FROM employee WHERE employee_no='{other_no}'"));employee_token=login(username)
body={'name':tag,'description':'每届四次季度答辩','batchId':batch,'effectiveMonth':'2020-01','quarterMonth1Weight':33.33,'quarterMonth2Weight':33.33,'quarterMonth3Weight':33.34,'bonusCap':0,'deductionCap':0,'stationAggregationMode':'AUTO_BY_DAYS','examSourceWeights':[],'taskSourceWeights':[]}
for key,weight in [('exam',0),('task',0),('mentor',100),('station',0),('training',0),('defense',20)]:body.update({key+'Enabled':weight>0,key+'Weight':weight,key+'MaxScore':100})
bad=dict(body,mentorWeight=80);request('POST','/evaluation/templates',bad,admin,400)
bad=dict(body,defenseWeight=100.01);request('POST','/evaluation/templates',bad,admin,400)
check('monthly weights must total 100 independently of the quarterly defense weight')
template=request('POST','/evaluation/templates',body,admin);copy=request('POST',f'/evaluation/templates/{template}/copy',{},admin)
check('new and copied templates retain quarterly defense scope',sql(f"SELECT count(*) FROM evaluation_template WHERE id IN ({template},{copy}) AND defense_scope='QUARTER' AND defense_weight=20")=='2')
scheme=request('POST','/evaluation/templates/apply',{'templateId':template,'batchId':batch,'effectiveMonth':'2020-01'},admin);request('POST',f'/evaluation/schemes/{scheme}/publish',{},admin)
check('applied scheme carries the quarterly policy',sql(f"SELECT defense_scope FROM score_scheme WHERE id={scheme}")=='QUARTER')
sessions=request('GET',f'/evaluation/defenses/sessions?batchId={batch}',token=admin)
check('each cohort has exactly four slots without creating read-side records',len(sessions)==4 and sql(f'SELECT count(*) FROM defense_session WHERE talent_batch_id={batch}')=='0')
request('PUT',f'/evaluation/defenses/sessions/{batch}/5',{'periodKey':'2032-Q4','version':0},admin,400)
periods=['2030-Q4','2031-Q1','2031-Q2','2031-Q3'];saved=[]
for stage,key in enumerate(periods,1):saved.append(request('PUT',f'/evaluation/defenses/sessions/{batch}/{stage}',{'periodKey':key,'version':0},admin))
session=saved[0]['id'];key=periods[0]
check('four training-quarter sessions can span two calendar years',[s['period_key'] for s in saved]==periods)
request('PUT',f'/evaluation/defenses/sessions/{batch}/2',{'periodKey':key,'version':1},admin,400)
request('PUT',f'/evaluation/defenses/sessions/{batch}/1',{'periodKey':key,'version':0},admin,409)
check('duplicate target quarters and stale session edits are rejected')
for person in [employee,other]:
    for month,score in [('2030-10',80),('2030-11',90),('2030-12',70)]:
        sql(f"INSERT INTO monthly_evaluation(employee_id,period_month,evaluator_type,evaluator_user_id,scope_id,score,comment) VALUES({person},'{month}-01','MENTOR',{mentor},0,{score},'QA'); INSERT INTO score_summary(employee_id,summary_type,period_key,version,scheme_id,mentor_score,final_score,status,component_snapshot) VALUES({person},'MONTH','{month}',1,{scheme},{score},{score},'PUBLISHED',JSON_OBJECT('components',JSON_ARRAY()))")
monthly_before=sql(f"SELECT group_concat(concat(id,':',status,':',final_score) ORDER BY id) FROM score_summary WHERE employee_id={employee} AND summary_type='MONTH'")
monthly=request('GET',f'/evaluation/monthly/detail?employeeId={employee}&month=2030-10',token=admin)
check('monthly calculation has five daily components and never requires defense',len(monthly['components'])==5 and all(x['code']!='DEFENSE' for x in monthly['components']) and monthly['finalScore']==80)
request('POST','/evaluation/summaries/generate-quarter?year=2030&quarter=4',{},admin)
def summaries(person=employee):return request('GET',f'/evaluation/summaries?employeeId={person}',token=admin)
def quarter(person=employee,status='DRAFT'):return next(x for x in summaries(person) if x['summary_type']=='QUARTER' and x['period_key']==key and x['status']==status)
q=quarter();request('POST',f"/evaluation/summaries/{q['id']}/publish",{},admin,400)
check('missing quarterly defense blocks the quarter while monthly results stay complete','DEFENSE' in q['missing_items'] and q['final_score'] is None)
def save(score,revision=None,person=employee):return request('POST','/evaluation/defenses',{'employeeId':person,'sessionId':session,'score':score,'comment':'季度答辩QA','revisionOf':revision},admin)
request('POST','/evaluation/defenses',{'employeeId':employee,'sessionId':session,'score':90.251},admin,400)
request('POST','/evaluation/defenses',{'employeeId':employee,'sessionId':session,'score':100.01},admin,400)
foreign=int(sql(f'SELECT min(id) FROM employee WHERE batch_id<>{batch}'))
request('POST','/evaluation/defenses',{'employeeId':foreign,'sessionId':session,'score':90},admin,400)
check('entry enforces cohort, full score and two decimal places')
grade=save(90.25);request('POST',f'/evaluation/defenses/{grade}/validate',{},admin);request('POST',f'/evaluation/defenses/{grade}/publish',{},admin)
q=quarter();check('published defense refreshes only the quarter draft with one weighted contribution',q['final_score']==82.05 and q['quarter_snapshot']['monthlyWeight']==80 and q['quarter_snapshot']['defense']['contribution']==18.05)
request('POST',f"/evaluation/summaries/{q['id']}/publish",{},admin)
request('PUT',f'/evaluation/defenses/sessions/{batch}/1',{'periodKey':'2030-Q3','version':1},admin,409)
request('POST','/evaluation/defenses',{'employeeId':employee,'sessionId':session,'score':91},admin,409)
check('published sessions lock their quarter and require explicit score revisions')
revised=save(95.55,grade);request('POST',f'/evaluation/defenses/{revised}/publish',{},admin)
group=int(sql(f"SELECT m.group_id FROM evaluation_revision_member m JOIN evaluation_revision_group g ON g.id=m.group_id WHERE m.employee_id={employee} AND g.status='DRAFT' ORDER BY g.id DESC LIMIT 1"));review=request('POST',f'/evaluation/revisions/{group}/refresh',{},admin)
check('defense correction creates a quarter-only revision and preserves all monthly versions',len(review['members'])==1 and review['members'][0]['summary_type']=='QUARTER' and monthly_before==sql(f"SELECT group_concat(concat(id,':',status,':',final_score) ORDER BY id) FROM score_summary WHERE employee_id={employee} AND summary_type='MONTH'"))
check('old official quarter remains active during review',quarter(status='PUBLISHED')['final_score']==82.05)
third=save(97.55,revised);request('POST',f'/evaluation/defenses/{third}/publish',{},admin)
request('POST',f'/evaluation/revisions/{group}/publish',{'reviewToken':review['reviewToken']},admin,409)
review=request('POST',f'/evaluation/revisions/{group}/refresh',{},admin);request('POST',f'/evaluation/revisions/{group}/publish',{'reviewToken':review['reviewToken']},admin)
check('stale defense source cannot publish; reviewed quarter replaces the old version',quarter(status='PUBLISHED')['final_score']==83.51 and sql(f"SELECT status FROM score_summary WHERE id={q['id']}")=='SUPERSEDED')
other_batch=int(sql(f'SELECT min(id) FROM talent_batch WHERE id<>{batch}'))
request('GET',f'/evaluation/defenses/sessions?batchId={other_batch}',token=employee_token,expected=403)
request('GET',f'/evaluation/defenses/history?employeeId={other}&sessionId={session}',token=employee_token,expected=403)
check('employee scope cannot view another cohort or person')
def workbook(values,sheet="答辩成绩"):
    target=io.BytesIO()
    with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
        z.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
        z.writestr('xl/workbook.xml','<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="SHEETNAME" sheetId="1" r:id="rId1"/></sheets></workbook>'.replace('SHEETNAME',escape(sheet)))
        z.writestr('xl/_rels/workbook.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
        rows=''.join('<row r="%s">%s</row>'%(n,''.join('<c r="%s%s" t="inlineStr"><is><t>%s</t></is></c>'%(chr(65+i),n,escape(str(value))) for i,value in enumerate(row))) for n,row in enumerate(values,1))
        z.writestr('xl/worksheets/sheet1.xml','<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'+rows+'</sheetData></worksheet>')
    return target.getvalue()

def upload(values,revision=False):
    if not isinstance(values,bytes):values=workbook([['工号','姓名','答辩成绩','评价意见']]+values)
    boundary='QuarterDefenseQA'
    content=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="quarter.xlsx"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n').encode()+values+f'\r\n--{boundary}--\r\n'.encode()
    return request('POST',f'/evaluation/defenses/imports?sessionId={session}&revision={str(revision).lower()}',content,admin,content_type='multipart/form-data; boundary='+boundary)
invalid_bytes=workbook([['工号','姓名','答辩成绩','评价意见'],[other_no,'季度答辩QA第二人','85.251','precision'],[other_no,'季度答辩QA第二人','','empty']])
invalid=upload(invalid_bytes);again=upload(invalid_bytes)
check('same file is idempotent within a session and invalid decimal or empty rows stay errors',invalid['id']==again['id'] and all(x['status']=='ERROR' for x in invalid['rows']))
request('POST',f"/evaluation/defenses/imports/{invalid['id']}/publish",{},admin,400)
zero=upload([[other_no,'季度答辩QA第二人','0.00','zero']]);request('POST',f"/evaluation/defenses/imports/{zero['id']}/publish",{},admin)
check('leading-zero employee number matches exactly and actual zero is published',sql(f"SELECT score FROM defense_result WHERE employee_id={other} AND session_id={session} AND status='PUBLISHED'")=='0.00')
conflict_values=workbook([['工号','姓名','答辩成绩','评价意见'],[other_no,'季度答辩QA第二人','45.55','revision']]);conflict=upload(conflict_values)
check('existing current grade blocks an implicit replacement',conflict['rows'][0]['status']=='ERROR')
replacement=upload(conflict_values,True);request('POST',f"/evaluation/defenses/imports/{replacement['id']}/publish",{},admin)
check('explicit same-file revision preserves previous published grade',replacement['id']==conflict['id'] and sql(f"SELECT count(*) FROM defense_result WHERE employee_id={other} AND session_id={session} AND status IN ('PUBLISHED','SUPERSEDED')")=='2')
url=BASE+f'/evaluation/defenses/export?sessionId={session}'
raw=urllib.request.urlopen(urllib.request.Request(url,headers={'Authorization':'Bearer '+admin})).read()
with zipfile.ZipFile(io.BytesIO(raw)) as z:
    xml=z.read('xl/worksheets/sheet1.xml').decode();styles=z.read('xl/styles.xml').decode()
check('export includes numeric decimal scores with two-place Excel format','t="n"><v>45.55</v>' in xml and ('numFmtId="2"' in styles or '0.00' in styles))
history=request('GET',f'/evaluation/defenses/history?employeeId={employee}&sessionId={session}',token=employee_token)
check('employee can inspect its three published defense revisions',len(history)==3 and all(x['status'] in ['PUBLISHED','SUPERSEDED'] for x in history))
check('no fifth session or repeated monthly defense source is created',sql(f'SELECT count(*) FROM defense_session WHERE talent_batch_id={batch}')=='4' and monthly_before==sql(f"SELECT group_concat(concat(id,':',status,':',final_score) ORDER BY id) FROM score_summary WHERE employee_id={employee} AND summary_type='MONTH'"))
print(json.dumps({'passed':len(checks),'checks':checks,'batch':batch,'session':session,'employee':employee,'username':username},ensure_ascii=False,indent=2))
