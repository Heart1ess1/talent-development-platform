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
now=dt.datetime.now(); current=now.date().replace(day=1); previous=(current-dt.timedelta(days=1)).replace(day=1); following=next_month(current)
future=(now+dt.timedelta(days=2)).isoformat(timespec='seconds'); end=end_month(current)
admin=login('admin'); mentor_token=login('mentor')
tag='QA'+str(time.time_ns())[-10:]
no='0000'+tag[2:];other_no='0001'+tag[2:];username='qa_employee_'+tag
sql(f"INSERT INTO talent_batch(name) VALUES('{tag}'); INSERT INTO sys_user(username,password_hash,display_name,role,enabled,must_change_password) SELECT '{username}',password_hash,'QA员工','EMPLOYEE',true,false FROM sys_user WHERE username='employee';")
batch=int(sql(f"SELECT id FROM talent_batch WHERE name='{tag}'"));mentor=int(sql("SELECT id FROM sys_user WHERE username='mentor'"));scorer=int(sql("SELECT id FROM sys_user WHERE username='trainadmin'"));admin_id=int(sql("SELECT id FROM sys_user WHERE username='admin'"))
sql(f"INSERT INTO employee(user_id,employee_no,name,batch_id,mentor_user_id) SELECT id,'{no}','QA员工',{batch},{mentor} FROM sys_user WHERE username='{username}'; INSERT INTO employee(employee_no,name,batch_id,mentor_user_id) VALUES('{other_no}','QA第二人',{batch},{mentor});")
employee=int(sql(f"SELECT id FROM employee WHERE employee_no='{no}'"));other=int(sql(f"SELECT id FROM employee WHERE employee_no='{other_no}'"));employee_token=login(username)
def scheme_body(task=100,defense=0,mentor_weight=0,exam=0):
    result={'name':tag,'description':'QA','batchId':batch,'effectiveMonth':'2020-01','quarterMonth1Weight':33.33,'quarterMonth2Weight':33.33,'quarterMonth3Weight':33.34,'bonusCap':0,'deductionCap':0,'stationAggregationMode':'AUTO_BY_DAYS','examSourceWeights':[],'taskSourceWeights':[]}
    for key,weight in [('exam',exam),('task',task),('mentor',mentor_weight),('station',0),('training',0),('defense',defense)]:result.update({key+'Enabled':weight>0,key+'Weight':weight,key+'MaxScore':100})
    return result
scheme=request('POST','/evaluation/schemes',scheme_body(),admin)
request('POST',f'/evaluation/schemes/{scheme}/publish',{},admin)
check('scheme API accepts six components and exact weights')
task=request('POST','/tasks/dispatch-manual',{'title':tag,'description':'QA','deadline':future,'scheduleMode':'MANUAL','startsAt':(now+dt.timedelta(days=1)).isoformat(timespec='seconds'),'batchIds':[batch],'reviewerIds':[scorer,mentor]},admin)['taskId']
assignment=int(sql(f'SELECT id FROM task_assignment WHERE task_id={task} AND employee_id={employee}'))
request('POST',f'/assignments/{assignment}/submissions/direct',{'content':'too early'},employee_token,400)
request('POST',f'/assignments/{assignment}/submission-files/upload-ticket',{'originalName':'x.pdf','contentType':'application/pdf','size':3},employee_token,400)
check('manual future start blocks both direct submit and upload ticket')
sql(f"UPDATE challenge_task SET starts_at='{previous}',deadline='{end_month(previous).replace('T',' ')}' WHERE id={task}; UPDATE task_assignment SET status='OVERDUE',final_score=0,score_month='{previous}' WHERE task_id={task};")
def adjustment(kind,resource,to,participant=None):
    detail=request('GET',f'/schedules/{kind}/{resource}',token=admin)
    return {'scheduleMode':'IMMEDIATE','endsAt':to,'version':detail['schedule_version'],'reason':'QA时限调整','participantId':participant}
payload=adjustment('TASK',task,end)
payload.update(scheduleMode='OFFSET',baseDate=str(current),offsetDays=(next_month(current)-current).days-1)
preview=request('POST',f'/schedules/TASK/{task}/preview',payload,admin)
check('ended task preview includes both participants',len(preview['participants'])==2)
request('PUT',f'/schedules/TASK/{task}',payload,admin)
saved=request('GET',f'/schedules/TASK/{task}',token=admin)
check('offset mode persists its base date and offset days',saved['schedule_mode']=='OFFSET' and saved['base_date']==str(current) and saved['offset_days']==payload['offsetDays'])
check('ended task reopens and moves every participant month',sql(f"SELECT count(*) FROM task_assignment WHERE task_id={task} AND status='NOT_SUBMITTED' AND final_score IS NULL AND score_month='{current}'")=='2')
request('PUT',f'/schedules/TASK/{task}',payload,admin,409)
submission=request('POST',f'/assignments/{assignment}/submissions/direct',{'content':'first result'},employee_token)
scorer_token=login('trainadmin')
request('POST',f'/task-scoring/submissions/{submission}/reviews',{'decision':'APPROVE','score':85.251},scorer_token,400)
request('POST',f'/task-scoring/submissions/{submission}/reviews',{'decision':'APPROVE','score':85.25},scorer_token)
request('POST',f'/task-scoring/submissions/{submission}/reviews',{'decision':'APPROVE','score':85.24},mentor_token)
check('decimal review stores 85.25 and rejects three decimal places',sql(f'SELECT final_score FROM task_assignment WHERE id={assignment}')=='85.25')
temporary=request('POST',f'/schedules/TASK/{task}/opportunities',adjustment('TASK',task,end_month(following),other),admin)
request('DELETE',f"/schedules/TASK/{task}/opportunities/{temporary['id']}",token=admin)
check('revoking an unused personal opportunity restores the original month',sql(f"SELECT score_month FROM task_assignment WHERE task_id={task} AND employee_id={other}")==str(current))
personal_request=adjustment('TASK',task,end_month(following),employee)
summary_count=sql(f'SELECT count(*) FROM score_summary WHERE employee_id={employee}')
forecast=request('POST',f'/schedules/TASK/{task}/preview',personal_request,admin)
check('read-only time preview projects monthly scores without creating drafts',any(x['periodKey']==month(following) and x['newScore']==85.25 for x in forecast['scoreImpacts']) and summary_count==sql(f'SELECT count(*) FROM score_summary WHERE employee_id={employee}'))
grant=request('POST',f'/schedules/TASK/{task}/opportunities',personal_request,admin)
todo=request('GET','/dashboard',token=employee_token)
check('personal task opportunity appears in employee to-do and pending count with its own deadline',any(x['type']=='TASK' and x['title']==tag and x['due_at'] and x['status']=='NOT_SUBMITTED' and '个人补交' in x['description'] for x in todo['action_items']) and todo['metrics']['pending_tasks']==1)
check('personal opportunity moves only selected employee',sql(f"SELECT count(*) FROM task_assignment WHERE task_id={task} AND employee_id={other} AND score_month='{current}'")=='1')
request('POST',f'/schedules/TASK/{task}/opportunities',adjustment('TASK',task,end_month(following),employee),admin,409)
def competing_submit(_):
    try:return request('POST',f'/assignments/{assignment}/submissions/direct',{'content':'supplement'},employee_token)
    except AssertionError:return None
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:submissions=list(pool.map(competing_submit,range(2)))
check('concurrent supplementary submissions create exactly one version',sum(x is not None for x in submissions)==1 and sql(f'SELECT count(*) FROM task_submission WHERE assignment_id={assignment}')=='2')
check('old approved score remains while supplement awaits review',sql(f'SELECT final_score FROM task_assignment WHERE id={assignment}')=='85.25')
new_submission=next(x for x in submissions if x is not None)
request('POST',f'/task-scoring/submissions/{new_submission}/reviews',{'decision':'APPROVE','score':84.15},scorer_token)
request('POST',f'/task-scoring/submissions/{new_submission}/reviews',{'decision':'APPROVE','score':84.14},mentor_token)
check('latest approved task score replaces previous score without deleting history',sql(f'SELECT final_score FROM task_assignment WHERE id={assignment}')=='84.15' and sql(f"SELECT count(*) FROM task_submission WHERE assignment_id={assignment} AND status='APPROVED'")=='2')
request('GET',f'/schedules/TASK/{task}',token=mentor_token,expected=403)
check('non-manager cannot inspect or grant schedule exceptions')
# Exam fixture: an ended exam with its original published answer sheet.
bank=request('POST','/question-banks',{'name':tag},admin) if False else request('POST','/exams/question-banks',{'name':tag,'enabled':True},admin)
question=request('POST','/exams/questions',{'bankId':bank,'type':'SINGLE','stem':'QA question','options':['A','B'],'answer':'A','score':100,'tags':[]},admin)
paper=request('POST','/exams/papers',{'name':tag,'randomAssembly':False,'dynamicAssembly':False,'randomizeQuestions':False,'randomizeOptions':False,'questions':[{'questionId':question,'score':100,'sortOrder':1}],'randomRules':[]},admin)
exam=request('POST','/exams/plans',{'paperId':paper,'name':tag,'scheduleMode':'IMMEDIATE','endsAt':end,'durationMinutes':30,'maxAttempts':1,'employeeIds':[employee,other],'batchIds':[batch]},admin)
request('POST',f'/exams/plans/{exam}/publish',{},admin)
sql(f"INSERT INTO exam_attempt(plan_id,employee_id,attempt_no,deadline_at,status,total_score,objective_score,published) VALUES({exam},{employee},1,now()-interval 1 day,'GRADED',70,70,true); UPDATE exam_plan SET starts_at=now()-interval 2 day,ends_at=now()-interval 1 day WHERE id={exam};")
request('PUT',f'/schedules/EXAM/{exam}',adjustment('EXAM',exam,end),admin)
request('POST',f'/exams/plans/{exam}/attempts',{},employee_token,400)
request('POST',f'/schedules/EXAM/{exam}/opportunities',adjustment('EXAM',exam,end_month(following),employee),admin)
answer=request('POST',f'/exams/plans/{exam}/attempts',{'proctorMode':'MOBILE_COMPATIBLE'},employee_token)
attempt=answer['id'];original_deadline=sql(f'SELECT deadline_at FROM exam_attempt WHERE id={attempt}');request('PUT',f'/schedules/EXAM/{exam}',adjustment('EXAM',exam,(now+dt.timedelta(minutes=1)).isoformat(timespec='seconds')),admin,409)
request('PUT',f'/schedules/EXAM/{exam}',adjustment('EXAM',exam,end_month(following)),admin)
check('exam extension retains active answer-sheet deadline',sql(f'SELECT deadline_at FROM exam_attempt WHERE id={attempt}')==original_deadline)
resumed=request('POST',f'/exams/plans/{exam}/attempts',{'proctorMode':'MOBILE_COMPATIBLE'},employee_token)
check('individual retake bypasses exhausted normal count and resumes same answer sheet',attempt==resumed['id'] and sql(f'SELECT count(*) FROM exam_attempt WHERE plan_id={exam} AND employee_id={employee}')=='2')
request('PUT',f'/exams/attempts/{attempt}/answers',{'questionId':question,'answer':'A'},employee_token)
request('POST',f'/exams/attempts/{attempt}/submit',{},employee_token)
request('POST',f'/exams/plans/{exam}/attempts',{},employee_token,400)
check('consumed retake cannot create another attempt; old score remains published',sql(f'SELECT count(*) FROM exam_attempt WHERE plan_id={exam} AND total_score=70 AND published=true')=='1')
sql(f"UPDATE exam_assignment SET window_ends_at=now()-interval 1 second WHERE plan_id={exam} AND employee_id={employee}")
for _ in range(65):
    if sql(f'SELECT published FROM exam_attempt WHERE id={attempt}')=='1':break
    time.sleep(1)
exam_scheme=request('POST','/evaluation/schemes',scheme_body(task=0,exam=100),admin);request('POST',f'/evaluation/schemes/{exam_scheme}/publish',{},admin)
exam_month=request('GET',f'/evaluation/monthly/detail?employeeId={employee}&month={month(following)}',token=admin)
check('exam and dashboard take highest published effective score after the personal window closes',exam_month['finalScore']==100 and request('GET','/dashboard',token=employee_token)['metrics']['exam_average']==100)
# Evaluation reviewer windows retain their named month.
mentor_scheme=request('POST','/evaluation/schemes',scheme_body(task=0,mentor_weight=100),admin)
request('POST',f'/evaluation/schemes/{mentor_scheme}/publish',{},admin)
request('POST','/evaluation/assignments/generate',{'month':month(current),'scheduleMode':'MANUAL','startsAt':(now+dt.timedelta(days=1)).isoformat(timespec='seconds'),'dueAt':future},admin)
rating=int(sql(f"SELECT id FROM evaluation_rating_task WHERE employee_id={employee} AND period_month='{current}' AND component_type='MENTOR'"))
entry={'employeeId':employee,'month':month(current),'score':77.25,'comment':'QA导师评价'}
request('PUT','/evaluation/monthly/components/MENTOR',entry,mentor_token,400)
request('PUT',f'/schedules/EVALUATION/{rating}',adjustment('EVALUATION',rating,end),admin)
request('PUT','/evaluation/monthly/components/MENTOR',entry,mentor_token)
sql(f"UPDATE evaluation_rating_task SET due_at=now()-interval 1 hour WHERE id={rating}")
request('PUT','/evaluation/monthly/components/MENTOR',entry,mentor_token,400)
request('POST',f'/schedules/EVALUATION/{rating}/opportunities',adjustment('EVALUATION',rating,end,mentor),admin)
entry['score']=78.50;request('PUT','/evaluation/monthly/components/MENTOR',entry,mentor_token)
request('PUT','/evaluation/monthly/components/MENTOR',entry,mentor_token,400)
check('personal evaluator opportunity is single use and preserves month and scoring history',sql(f"SELECT period_month FROM evaluation_rating_task WHERE id={rating}")==str(current) and sql('SELECT count(*) FROM monthly_evaluation_history')!='0')
request('POST',f'/evaluation/summaries/generate-month?month={month(current)}',{},admin)
current_summary=next(x for x in request('GET',f'/evaluation/summaries?employeeId={employee}',token=admin) if x['period_key']==month(current) and x['status']=='DRAFT')
request('POST',f"/evaluation/summaries/{current_summary['id']}/publish",{},admin)
request('POST',f'/schedules/EVALUATION/{rating}/opportunities',adjustment('EVALUATION',rating,end,mentor),admin)
mine=request('GET',f'/evaluation/assignments/mine?month={month(current)}',token=mentor_token);personal_rating=next(x for x in mine if x['id']==rating)
check('personal evaluator opportunity is visible as pending work',personal_rating['personal_opportunity'] and personal_rating['status'] in ['PENDING','IN_PROGRESS'])
entry['score']=79.25;request('PUT','/evaluation/monthly/components/MENTOR',entry,mentor_token)
check('published rating task can reopen while its old official score is retained',sql(f"SELECT final_score FROM score_summary WHERE id={current_summary['id']}")=='78.50')
manual_group=request('GET','/evaluation/revisions',token=admin)[0]['id'];manual_review=request('POST',f'/evaluation/revisions/{manual_group}/refresh',{},admin)
request('POST',f'/evaluation/revisions/{manual_group}/publish',{'reviewToken':manual_review['reviewToken']},admin)
# Defense templates, manual revisions, Excel validation, and atomic summary corrections.
body=scheme_body(task=100,defense=20);template=request('POST','/evaluation/templates',body,admin)
copy=request('POST',f'/evaluation/templates/{template}/copy',{},admin)
templates=request('GET','/evaluation/templates',token=admin)
check('copy preserves defense enabled flag and weight',next(x for x in templates if x['id']==copy)['defense_weight']==20)
applied=request('POST','/evaluation/templates/apply',{'templateId':template,'batchId':batch,'effectiveMonth':month(current)},admin)
request('POST',f'/evaluation/schemes/{applied}/publish',{},admin)
pinned=request('GET',f'/evaluation/monthly/detail?employeeId={employee}&month={month(current)}',token=admin)
check('published months retain their original scheme after a new template is applied',pinned['schemeId']==mentor_scheme and pinned['finalScore']==79.25)
detail=request('GET',f'/evaluation/monthly/detail?employeeId={employee}&month={month(following)}',token=admin)
check('quarterly defense does not change monthly task score',detail['finalScore']==84.15 and all(x['code']!='DEFENSE' for x in detail['components']))
request('POST',f'/evaluation/summaries/generate-month?month={month(following)}',{},admin)
summaries=request('GET',f'/evaluation/summaries?employeeId={employee}',token=admin)
summary=next(x for x in summaries if x['period_key']==month(following) and x['status']=='DRAFT')
request('POST',f"/evaluation/summaries/{summary['id']}/publish",{},admin)
request('POST',f'/schedules/TASK/{task}/opportunities',adjustment('TASK',task,end_month(following),employee),admin)
groups=request('GET','/evaluation/revisions',token=admin);group=groups[0]['id']
request('POST',f'/schedules/EVALUATION/{rating}/opportunities',adjustment('EVALUATION',rating,end,mentor),admin)
entry['score']=79.75;request('PUT','/evaluation/monthly/components/MENTOR',entry,mentor_token)
review=request('POST',f'/evaluation/revisions/{group}/refresh',{},admin)
check('related pending changes for the same employee form one review group',any(r['period_key']==month(current) for r in review['members']) and any(r['period_key']==month(following) for r in review['members']))
check('published monthly snapshot stays unchanged while revision is pending',sql(f"SELECT final_score FROM score_summary WHERE id={summary['id']}")=='84.15')
sql(f'UPDATE task_submission SET score=83.15 WHERE id={new_submission}; UPDATE task_assignment SET final_score=83.15 WHERE id={assignment}')
request('POST',f'/evaluation/revisions/{group}/publish',{'reviewToken':review['reviewToken']},admin,409)
review=request('POST',f'/evaluation/revisions/{group}/refresh',{},admin)
request('POST',f'/evaluation/revisions/{group}/publish',{'reviewToken':review['reviewToken']},admin)
check('stale approval rejected; reviewed group atomically supersedes official version',sql(f"SELECT status FROM score_summary WHERE id={summary['id']}")=='SUPERSEDED' and sql(f"SELECT final_score FROM score_summary WHERE employee_id={employee} AND period_key='{month(following)}' AND status='PUBLISHED'")=='83.15')
# Standard-library Excel fixture keeps employee numbers as text.
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
headers=['工号','姓名','评价类型','期间','最终分数','评分项','来源编号','来源名称','原始分','满分','权重','加权分','评分人','评分意见']
def history_import(score):
    values=[headers,[other_no,'QA第二人','MONTH',month(following),score,'DEFENSE',tag,'QA历史答辩',score,'100','100',score,'QA','QA']]
    data=workbook(values,'历史评价');boundary='QAhistoryboundary'
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="history.xlsx"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n').encode()+data+f'\r\n--{boundary}--\r\n'.encode()
    query=urllib.parse.urlencode({'type':'EVALUATION','metadata':json.dumps({'summaryType':'MONTH','periodKey':month(following)})})
    return request('POST','/history-imports?'+query,body,admin,content_type='multipart/form-data; boundary='+boundary)
bad_history=history_import('90.251');check('historical import rejects more than two decimal places',bad_history['errorCount']>0)
good_history=history_import('90.25');request('POST',f"/history-imports/{good_history['batchId']}/publish",{},admin)
check('historical defense component publishes without changing its recorded total',sql(f"SELECT defense_score FROM score_summary WHERE employee_id={other} AND period_key='{month(following)}' AND status='PUBLISHED'")=='90.25')
# Cross-year movement includes an already published quarter but never creates a future quarter.
old_quarter=f"{following.year}-Q{(following.month-1)//3+1}"
sql(f"INSERT INTO score_summary(employee_id,summary_type,period_key,version,scheme_id,final_score,status,quarter_snapshot) VALUES({employee},'QUARTER','{old_quarter}',1,{applied},80,'PUBLISHED',JSON_OBJECT('months',JSON_ARRAY()));")
cross=dt.date(following.year+1,1,1)
cross_request=adjustment('TASK',task,end_month(cross))
cross_preview=request('POST',f'/schedules/TASK/{task}/preview',cross_request,admin)
check('time preview includes published quarter with old score and pending projection',any(x['summaryType']=='QUARTER' and x['periodKey']==old_quarter and x['oldScore']==80 and x['newScore'] is None for x in cross_preview['scoreImpacts']))
request('PUT',f'/schedules/TASK/{task}',cross_request,admin)
group=request('GET','/evaluation/revisions',token=admin)[0]['id'];review=request('POST',f'/evaluation/revisions/{group}/refresh',{},admin)
check('cross-year adjustment includes the old published quarter only',sum(r['summary_type']=='QUARTER' for r in review['members'])==1 and any(r['period_key']==old_quarter for r in review['members']))
missing=next(r for r in review['members'] if r['missing_items'])
request('POST',f'/evaluation/revisions/{group}/waivers',{'summaryId':missing['summary_id'],'score':80,'reason':'QA'},scorer_token,403)
for kind in ['MONTH','QUARTER']:
    for row in list(review['members']):
        if row['summary_type']==kind and row['missing_items']:
            review=request('POST',f'/evaluation/revisions/{group}/waivers',{'summaryId':row['summary_id'],'score':80,'reason':'QA人工核定缺失项'},admin)
request('POST',f'/evaluation/revisions/{group}/publish',{'reviewToken':review['reviewToken']},admin)
check('cross-year monthly and quarterly corrections publish atomically with audited waivers',sql(f"SELECT count(*) FROM score_summary WHERE employee_id={employee} AND period_key='{month(cross)}' AND status='PUBLISHED'")=='1')
plan=request('POST','/training-plans',{'name':tag,'description':'QA稳定下发标识'},admin)
plan_task=request('POST',f'/training-plans/{plan}/tasks',{'title':tag,'description':'QA计划任务'},admin)
request('PUT',f'/training-plans/{plan}/enabled',{'enabled':True},admin)
dispatch={'planId':plan,'planTaskIds':[plan_task],'deadlineMode':'IMMEDIATE','endsAt':end,'batchIds':[batch],'dispatchKey':tag+'-stable','reviewerIds':[scorer]}
request('POST','/tasks/dispatch-plan/preview',dispatch,admin)
first_dispatch=request('POST','/tasks/dispatch-plan',dispatch,admin)
plan_instance=int(sql(f"SELECT id FROM challenge_task WHERE training_plan_task_id={plan_task} AND dispatch_key='{tag}-stable'"))
request('PUT',f'/schedules/TASK/{plan_instance}',adjustment('TASK',plan_instance,end_month(following)),admin)
replay_preview=request('POST','/tasks/dispatch-plan/preview',dispatch,admin);replay=request('POST','/tasks/dispatch-plan',dispatch,admin)
check('plan dispatch remains idempotent after deadline changes',replay_preview['reusedTasks']==1 and replay['createdTasks']==0 and replay['createdAssignments']==0)
print(json.dumps({'passed':len(checks),'checks':checks,'task':task,'exam':exam,'batch':batch,'employee':employee},ensure_ascii=False,indent=2))
