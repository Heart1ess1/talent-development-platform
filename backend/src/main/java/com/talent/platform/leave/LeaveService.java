package com.talent.platform.leave;

import com.talent.platform.common.BusinessException;
import com.talent.platform.common.PageResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talent.platform.security.CurrentUser;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.SecurityUtils;
import com.talent.platform.security.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class LeaveService {
  private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final ObjectMapper mapper;
  private final AuditService audit;
  public LeaveService(JdbcTemplate db, PermissionService permissions,ObjectMapper mapper,AuditService audit) { this.db=db; this.permissions=permissions; this.mapper=mapper; this.audit=audit; }

  public record Form(String scenario,String leaveType,LocalDateTime startAt,LocalDateTime endAt,
                     String destination,String returnLocation,String reason,String supplementalReason,
                     Long approverUserId,Boolean noticeAcknowledged,String requestKey,Integer expectedVersion) {}
  public record ReturnForm(LocalDateTime actualReturnAt,String actualReturnLocation,String explanation,Integer expectedVersion) {}
  public record ReviewForm(String action,String comment) {}
  public record TransferForm(Long approverUserId,String reason) {}

  public Map<String,Object> preview(LocalDateTime start,LocalDateTime end) {
    ownEmployee();
    var duration=LeaveDuration.calculate(start,end);
    long station=employeeStation(SecurityUtils.current().id());
    return Map.of("halfDays",duration.halfDays(),"days",duration.halfDays()/2.0,
        "level",duration.level(),"periods",duration.periods(),"approvers",candidates(duration.level(),station));
  }

  public Map<String,Object> context() {
    CurrentUser user=SecurityUtils.current();
    if("EMPLOYEE".equals(user.role())){
      var rows=db.queryForList("select e.id,e.name,e.employee_no,e.station_id,s.name station_name from employee e left join service_station s on s.id=e.station_id where e.user_id=?",user.id());
      return Map.of("employee",rows.isEmpty()?Map.of():rows.get(0),"stations",List.of());
    }
    if(!user.can("leave:view"))return Map.of("employee",Map.of(),"stations",List.of());
    var scope=permissions.employeeFilter("e");
    return Map.of("employee",Map.of(),"stations",db.queryForList("select distinct s.id,s.name from service_station s join employee e on e.station_id=s.id where 1=1"+scope.sql()+" order by s.name",scope.args().toArray()));
  }

  public List<Map<String,Object>> transferCandidates(long id,int number){
    if(!SecurityUtils.admin(SecurityUtils.current()))throw new BusinessException(403,"仅管理员可转交");
    var row=version(id,number);
    return candidates(String.valueOf(row.get("approver_level")),((Number)row.get("station_id")).longValue());
  }

  public Map<String,Object> draft() {
    long employee=ownEmployee();
    var rows=db.queryForList("select content from leave_draft where employee_id=?",employee);
    if(rows.isEmpty())return Map.of();
    try{return mapper.readValue(String.valueOf(rows.get(0).get("content")),new TypeReference<>() {});}
    catch(Exception e){throw new IllegalStateException("请假草稿读取失败",e);}
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public void saveDraft(Map<String,Object> content) {
    long employee=ownEmployee();
    try{
      String json=mapper.writeValueAsString(content);
      if(json.length()>10000)throw bad("草稿内容过长");
      db.update("insert into leave_draft(employee_id,content) values(?,?) on duplicate key update content=values(content),updated_at=now()",employee,json);
    }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw bad("草稿格式无效");}
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public void deleteDraft() {db.update("delete from leave_draft where employee_id=?",ownEmployee());}

  public List<Map<String,Object>> candidates(String level,long station) {
    if (!List.of("STATION_MANAGER","AREA_MANAGER","TRAINING_DIRECTOR").contains(level)) throw bad("审批级别无效");
    String join=switch(level) {
      case "STATION_MANAGER" -> "join station_manager_scope scope on scope.user_id=u.id and scope.station_id=?";
      case "AREA_MANAGER" -> "join area_manager_scope scope on scope.user_id=u.id and scope.station_id=?";
      default -> "";
    };
    return db.queryForList("select distinct u.id,u.display_name,u.role from sys_user u " + join +
        " where u.role=? and u.enabled=true order by u.display_name,u.id",
        level.equals("TRAINING_DIRECTOR") ? new Object[]{level} : new Object[]{station,level});
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public long submit(Form form,Long requestId) {
    CurrentUser user=SecurityUtils.current();
    if (!"EMPLOYEE".equals(user.role())) throw bad("仅员工可申请请假");
    validate(form);
    if (blank(form.requestKey()) || form.requestKey().length()>64) throw bad("提交标识无效");
    long employee=employeeId(user.id());
    lockEmployee(employee);
    String hash=hash(form.toString()+":"+requestId);
    var previously=db.queryForList("select request_id,payload_hash from leave_submission_key where user_id=? and request_key=? for update",user.id(),form.requestKey());
    if (!previously.isEmpty()) {
      if(!hash.equals(previously.get(0).get("payload_hash")))throw new BusinessException(409,"重复提交标识对应的内容已变化，请重新打开申请");
      return ((Number)previously.get(0).get("request_id")).longValue();
    }
    db.update("insert into leave_submission_key(user_id,request_key,payload_hash) values(?,?,?)",user.id(),form.requestKey(),hash);
    long station=employeeStation(user.id());
    db.queryForList("select id from sys_user where id=? for update",form.approverUserId());
    var duration=LeaveDuration.calculate(form.startAt(),form.endAt());
    if (candidates(duration.level(),station).stream().noneMatch(x -> ((Number)x.get("id")).longValue()==form.approverUserId()))
      throw bad("审批人不在当前层级或服务站范围内，请重新选择");
    boolean supplemental=form.startAt().isBefore(now());
    if (supplemental && blank(form.supplementalReason())) throw bad("补提申请必须填写说明");
    if (requestId==null) {
      rejectOverlap(employee,form.startAt(),form.endAt(),null);
      db.update("insert into leave_request(employee_id,current_version,status) values(?,1,'PENDING')",employee);
      requestId=db.queryForObject("select last_insert_id()",Long.class);
    } else {
      var current=locked(requestId);
      owner(current,user);
      if (!"APPROVED".equals(current.get("status"))) throw bad("只有已批准且未销假的请假可以申请变更");
      requireVersion(current,form.expectedVersion());
      if (pendingVersion(requestId)!=null) throw bad("已有待审批的变更申请");
      rejectOverlap(employee,form.startAt(),form.endAt(),requestId);
    }
    // The approved version remains current while a change waits for review.
    Integer existing=db.queryForObject("select coalesce(max(version_no),0) from leave_request_version where request_id=?",Integer.class,requestId);
    int version=existing+1;
    db.update("""
        insert into leave_request_version(request_id,version_no,scenario,leave_type,start_at,end_at,destination,
          return_location,reason,supplemental_reason,is_supplemental,day_units,approver_level,approver_user_id,
          station_id,status,notice_version,notice_acknowledged_at,created_by)
        values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING','V1',now(),?)
        """,requestId,version,form.scenario(),form.leaveType(),form.startAt(),form.endAt(),
        form.destination().trim(),form.returnLocation().trim(),form.reason().trim(),
        supplemental?form.supplementalReason().trim():null,supplemental,duration.halfDays(),duration.level(),
        form.approverUserId(),station,user.id());
    action(requestId,version,user.id(),version==1?"SUBMIT":"CHANGE_SUBMIT",null,null,null);
    db.update("update leave_submission_key set request_id=? where user_id=? and request_key=?",requestId,user.id(),form.requestKey());
    db.update("delete from leave_draft where employee_id=?",employee);
    return requestId;
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public void review(long requestId,int version,ReviewForm form) {
    var request=locked(requestId);
    var candidate=version(requestId,version);
    CurrentUser user=SecurityUtils.current();
    if (((Number)candidate.get("approver_user_id")).longValue()!=user.id()) throw new BusinessException(403,"仅指定审批人可审批");
    db.queryForList("select id from sys_user where id=? for update",user.id());
    if(form==null||form.action()==null||!List.of("APPROVE","REJECT").contains(form.action()))throw bad("审批操作无效");
    String state="APPROVE".equals(form.action())?"APPROVED":"REJECTED";
    if(state.equals(candidate.get("status")))return;
    if (!"PENDING".equals(candidate.get("status"))) throw bad("该申请已处理");
    long station=((Number)candidate.get("station_id")).longValue();
    if (candidates(String.valueOf(candidate.get("approver_level")),station).stream().noneMatch(x -> ((Number)x.get("id")).longValue()==user.id()))
      throw new BusinessException(403,"审批资格或管理范围已变更");
    if ("REJECT".equals(form.action()) && blank(form.comment())) throw bad("驳回原因必填");
    if(form.comment()!=null&&form.comment().length()>1000)throw bad("审批意见最多1000字");
    if("APPROVED".equals(state)) {
      rejectOverlap(((Number)request.get("employee_id")).longValue(),local(candidate.get("start_at")),local(candidate.get("end_at")),requestId);
      if(!local(candidate.get("start_at")).isAfter(now())) {
        Integer unsettled=db.queryForObject("select count(*) from leave_request r join leave_request_version v on v.request_id=r.id and v.version_no=r.current_version where r.employee_id=? and r.id<>? and r.status='APPROVED' and v.start_at<=?",Integer.class,request.get("employee_id"),requestId,now());
        if(unsettled!=null&&unsettled>0)throw bad("该员工尚有未销假单据，请先完成旧单销假");
      }
    }
    db.update("update leave_request_version set status=?,reviewed_at=now() where request_id=? and version_no=?",state,requestId,version);
    if ("APPROVED".equals(state)) {
      db.update("update leave_request set current_version=?,status='APPROVED' where id=?",version,requestId);
      db.update("update leave_reminder set status='CLOSED',completed_at=now() where request_id=? and status='OPEN'",requestId);
      overdueOne(requestId);
    } else if (version==1) db.update("update leave_request set status='REJECTED' where id=?",requestId);
    action(requestId,version,user.id(),state,form.comment(),null,null);
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public void withdraw(long id,int version) {
    var request=locked(id); owner(request,SecurityUtils.current());
    var row=version(id,version);
    if (!"PENDING".equals(row.get("status"))) throw bad("只能撤回待审批申请");
    db.update("update leave_request_version set status='WITHDRAWN' where request_id=? and version_no=?",id,version);
    if (version==1) db.update("update leave_request set status='WITHDRAWN' where id=?",id);
    action(id,version,SecurityUtils.current().id(),"WITHDRAW",null,null,null);
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public void cancel(long id,int expectedVersion) {
    var request=locked(id); owner(request,SecurityUtils.current());
    requireVersion(request,expectedVersion);
    if("CANCELLED".equals(request.get("status")))return;
    if (!"APPROVED".equals(request.get("status"))) throw bad("仅可取消已批准的请假");
    var active=version(id,((Number)request.get("current_version")).intValue());
    if (!local(active.get("start_at")).isAfter(now())) throw bad("请假已经开始，请办理销假");
    if (pendingVersion(id)!=null) throw bad("请先撤回待审批变更");
    db.update("update leave_request set status='CANCELLED' where id=?",id);
    db.update("update leave_reminder set status='CLOSED',completed_at=now() where request_id=? and status='OPEN'",id);
    action(id,((Number)request.get("current_version")).intValue(),SecurityUtils.current().id(),"CANCEL",null,null,null);
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public void returnFromLeave(long id,ReturnForm form) {
    var request=locked(id); owner(request,SecurityUtils.current());
    requireVersion(request,form.expectedVersion());
    if("RETURNED".equals(request.get("status")))return;
    if (!"APPROVED".equals(request.get("status"))) throw bad("当前请假不能销假");
    int version=((Number)request.get("current_version")).intValue();
    var active=version(id,version);
    if (form.actualReturnAt()==null || form.actualReturnAt().isAfter(now()) ||
        form.actualReturnAt().isBefore(local(active.get("start_at")))) throw bad("实际返回时间无效");
    if (blank(form.actualReturnLocation())) throw bad("请填写实际返回地点");
    if(form.actualReturnLocation().length()>128||(form.explanation()!=null&&form.explanation().length()>1000))throw bad("销假信息超过长度限制");
    boolean changed=form.actualReturnAt().isAfter(local(active.get("end_at"))) ||
        !form.actualReturnLocation().trim().equals(active.get("return_location"));
    if (changed && blank(form.explanation())) throw bad("晚归或返回地点不一致时必须填写说明");
    Integer pending=pendingVersion(id);
    if (pending!=null) {
      db.update("update leave_request_version set status='WITHDRAWN' where request_id=? and version_no=?",id,pending);
      action(id,pending,SecurityUtils.current().id(),"CHANGE_CLOSED","已销假，自动结束待审批变更",null,null);
    }
    db.update("update leave_request set status='RETURNED' where id=?",id);
    db.update("update leave_reminder set status='CLOSED',completed_at=now() where request_id=? and status='OPEN'",id);
    String note=form.explanation();
    action(id,version,SecurityUtils.current().id(),"RETURN",note,form.actualReturnAt(),form.actualReturnLocation().trim());
    String from=db.queryForObject("select coalesce((select to_location from employee_location_report where employee_id=? order by occurred_at desc,id desc limit 1),'未登记位置')",String.class,request.get("employee_id"));
    db.update("""
        insert into employee_location_report(employee_id,from_location,to_location,reason,occurred_at,
          report_source,reported_by,leave_request_id,leave_version_no,movement_kind)
        values(?,?,?,'请假销假返回',?,'LEAVE_RETURN',?,?,?,'ACTUAL')
        """,request.get("employee_id"),from,form.actualReturnLocation().trim(),form.actualReturnAt(),
        SecurityUtils.current().id(),id,version);
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public void transfer(long id,int version,TransferForm form) {
    if (!SecurityUtils.admin(SecurityUtils.current())) throw new BusinessException(403,"仅管理员可转交");
    var request=locked(id);
    var row=version(id,version);
    if (!"PENDING".equals(row.get("status")) && !("APPROVED".equals(request.get("status"))&&((Number)request.get("current_version")).intValue()==version)) throw bad("该版本不能转交");
    if (blank(form.reason()) || form.approverUserId()==null) throw bad("转交人员和原因必填");
    if(form.reason().length()>500)throw bad("转交原因最多500字");
    db.queryForList("select id from sys_user where id=? for update",form.approverUserId());
    if (candidates(String.valueOf(row.get("approver_level")),((Number)row.get("station_id")).longValue()).stream()
        .noneMatch(x -> ((Number)x.get("id")).longValue()==form.approverUserId())) throw bad("接收人资格或范围不符");
    db.update("update leave_request_version set approver_user_id=? where request_id=? and version_no=?",form.approverUserId(),id,version);
    db.update("update leave_reminder set status='CLOSED',completed_at=now() where request_id=? and version_no=? and status='OPEN'",id,version);
    action(id,version,SecurityUtils.current().id(),"TRANSFER","原审批人ID："+row.get("approver_user_id")+"；新审批人ID："+form.approverUserId()+"；"+form.reason(),null,null);
    overdueOne(id);
  }

  public PageResult<Map<String,Object>> list(String view,String keyword,String status,Long stationId,String scenario,
      String approvalStatus,String returnStatus,LocalDate dateFrom,LocalDate dateTo,int page,int size) {
    CurrentUser user=SecurityUtils.current();
    int limit=Math.min(100,Math.max(1,size)); int offset=Math.max(0,page-1)*limit;
    String where=" where 1=1";
    var args=new ArrayList<Object>();
    if ("EMPLOYEE".equals(user.role())) {
      where+=" and e.user_id=?"; args.add(user.id());
      if ("pending".equals(view)) where+=" and v.status='PENDING'";
      if ("active".equals(view)) where+=" and r.status='APPROVED' and v.version_no=r.current_version";
      if ("returned".equals(view)) where+=" and r.status='RETURNED' and v.version_no=r.current_version";
    }
    else if ("pending".equals(view) || "reviewed".equals(view) || "overdue".equals(view)) {
      where+="reviewed".equals(view)?" and exists(select 1 from leave_action a where a.request_id=r.id and a.version_no=v.version_no and a.actor_user_id=? and a.action in ('APPROVED','REJECTED'))":" and v.approver_user_id=?"; args.add(user.id());
      where+="pending".equals(view)?" and v.status='PENDING'":"reviewed".equals(view)?" and v.status in ('APPROVED','REJECTED')":" and r.status='APPROVED' and v.version_no=r.current_version and v.end_at<now()";
    } else {
      if (!user.can("leave:view")) throw new BusinessException(403,"无权查看请假记录");
      var scope=permissions.employeeFilter("e"); where+=scope.sql(); args.addAll(scope.args());
    }
    if (keyword!=null && !keyword.isBlank()) {where+=" and (e.name like ? or e.employee_no like ?)"; args.add("%"+keyword.trim()+"%"); args.add("%"+keyword.trim()+"%");}
    if (status!=null && !status.isBlank()) {where+=" and r.status=?"; args.add(status);}
    if (stationId!=null) {where+=" and v.station_id=?";args.add(stationId);}
    if (scenario!=null&&!scenario.isBlank()) {where+=" and v.scenario=?";args.add(scenario);}
    if (approvalStatus!=null&&!approvalStatus.isBlank()) {where+=" and v.status=?";args.add(approvalStatus);}
    if ("RETURNED".equals(returnStatus))where+=" and r.status='RETURNED'";
    if ("UNRETURNED".equals(returnStatus))where+=" and r.status='APPROVED'";
    if (dateFrom!=null){where+=" and v.end_at>?";args.add(dateFrom.atStartOfDay());}
    if (dateTo!=null){where+=" and v.start_at<?";args.add(dateTo.plusDays(1).atStartOfDay());}
    if(dateFrom!=null&&dateTo!=null&&dateTo.isBefore(dateFrom))throw bad("日期范围无效");
    String versionJoin="reviewed".equals(view)?"":" and (v.version_no=r.current_version or v.status='PENDING')";
    String base=" from leave_request r join employee e on e.id=r.employee_id join leave_request_version v on v.request_id=r.id"+versionJoin;
    Long total=db.queryForObject("select count(*)"+base+where,Long.class,args.toArray());
    args.add(limit);args.add(offset);
    var rows=db.queryForList("select r.id request_id,r.status request_status,r.current_version,e.name employee_name,e.employee_no,"+
        "v.*,(select name from service_station where id=v.station_id) station_name,"+
        "(select display_name from sys_user where id=v.approver_user_id) approver_name,"+
        "exists(select 1 from leave_request_version p where p.request_id=r.id and p.status='PENDING') has_pending_change,"+
        "(r.status='APPROVED' and v.version_no=r.current_version and v.end_at<now()) overdue"+
        base+where+" order by v.created_at desc,v.id desc limit ? offset ?",args.toArray());
    return new PageResult<>(rows,total==null?0:total,page,limit);
  }

  public Map<String,Object> detail(long id) {
    var rows=db.queryForList("select r.*,e.user_id,e.station_id from leave_request r join employee e on e.id=r.employee_id where r.id=?",id);
    if(rows.isEmpty())throw new BusinessException(404,"请假单不存在");
    var row=rows.get(0);
    CurrentUser user=SecurityUtils.current();
    if (!user.id().equals(((Number)row.get("user_id")).longValue()) && !SecurityUtils.admin(user)) {
      boolean selected=db.queryForObject("select count(*) from leave_request_version where request_id=? and approver_user_id=?",Integer.class,id,user.id())>0;
      if (!selected) {permissions.require("leave:view");permissions.requireEmployee(((Number)row.get("employee_id")).longValue());}
    }
    var versions=db.queryForList("select v.*,u.display_name approver_name from leave_request_version v join sys_user u on u.id=v.approver_user_id where v.request_id=? order by v.version_no desc",id);
    for(var item:versions)item.put("calculation_periods",LeaveDuration.calculate(local(item.get("start_at")),local(item.get("end_at"))).periods());
    return Map.of("request",row,
        "versions",versions,
        "actions",db.queryForList("select a.*,u.display_name actor_name,(a.action='RETURN' and a.actual_return_at>v.end_at) actual_late_return,(a.action='RETURN' and a.actual_return_at<=v.end_at and a.created_at>v.end_at) late_filing from leave_action a join sys_user u on u.id=a.actor_user_id join leave_request_version v on v.request_id=a.request_id and v.version_no=a.version_no where a.request_id=? order by a.id desc",id),
        "movements",db.queryForList("select * from employee_location_report where leave_request_id=? order by id desc",id));
  }

  public List<Map<String,Object>> plans(Long employeeId) {
    CurrentUser user=SecurityUtils.current();
    long employee;
    if (employeeId==null) {
      if (!"EMPLOYEE".equals(user.role())) throw bad("请指定员工");
      employee=employeeId(user.id());
    } else {
      employee=employeeId;
      permissions.requireEmployee(employee);
    }
    return db.queryForList("""
        select r.id request_id,r.status request_status,v.version_no,v.start_at,v.end_at,
          v.destination,v.return_location,v.day_units,u.display_name approver_name
        from leave_request r
        join leave_request_version v on v.request_id=r.id and v.version_no=r.current_version
        join sys_user u on u.id=v.approver_user_id
        where r.employee_id=? and r.status in ('APPROVED','RETURNED','CANCELLED')
        order by v.start_at desc,r.id desc limit 100
        """,employee);
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public void scanOverdue() {
    var ids=db.queryForList("""
        select r.id from leave_request r join leave_request_version v on v.request_id=r.id and v.version_no=r.current_version
        where r.status='APPROVED' and v.end_at<now()
          and not exists(select 1 from leave_reminder m where m.request_id=r.id and m.version_no=r.current_version
            and m.recipient_user_id=v.approver_user_id and m.status='OPEN')
        order by r.id
        """,Long.class);
    for (Long id:ids) overdueOne(id);
  }

  private void overdueOne(long id) {
    var request=locked(id);
    if (!"APPROVED".equals(request.get("status"))) return;
    int number=((Number)request.get("current_version")).intValue();
    var row=version(id,number);
    if (!local(row.get("end_at")).isBefore(now())) return;
    Long employeeUser=db.queryForObject("select user_id from employee where id=?",Long.class,request.get("employee_id"));
    for (Long recipient:List.of(employeeUser,((Number)row.get("approver_user_id")).longValue()))
      db.update("insert into leave_reminder(request_id,version_no,recipient_user_id) values(?,?,?) on duplicate key update status='OPEN',completed_at=null",id,number,recipient);
  }

  private void validate(Form f) {
    if (f==null || f.scenario()==null || f.leaveType()==null || !List.of("FIELD_ABSENCE","RETURN_COMPANY","RETURN_HOME","OTHER").contains(f.scenario()) ||
        !List.of("PERSONAL","OFFICIAL").contains(f.leaveType()) || blank(f.destination()) || blank(f.returnLocation()) ||
        blank(f.reason()) || f.approverUserId()==null || !Boolean.TRUE.equals(f.noticeAcknowledged())) throw bad("请完整填写请假信息并确认请假须知");
    if (f.destination().length()>128 || f.returnLocation().length()>128 || f.reason().length()>1000 ||
        (f.supplementalReason()!=null && f.supplementalReason().length()>500)) throw bad("请假信息超过长度限制");
    LeaveDuration.calculate(f.startAt(),f.endAt());
  }
  private void rejectOverlap(long employee,LocalDateTime start,LocalDateTime end,Long except) {
    Integer count=db.queryForObject("""
        select count(*) from leave_request r join leave_request_version v on v.request_id=r.id
        where r.employee_id=? and r.status in ('PENDING','APPROVED') and (v.version_no=r.current_version or v.status='PENDING')
          and v.status in ('PENDING','APPROVED') and v.start_at<? and v.end_at>? and (? is null or r.id<>?)
        """,Integer.class,employee,end,start,except,except);
    if (count!=null && count>0) throw bad("该时间段已有待审批或生效请假");
  }
  private long ownEmployee(){if(!"EMPLOYEE".equals(SecurityUtils.current().role()))throw new BusinessException(403,"仅员工可操作请假草稿");return employeeId(SecurityUtils.current().id());}
  private long employeeId(long user) {var rows=db.queryForList("select id from employee where user_id=? and status='ACTIVE'",Long.class,user);if(rows.isEmpty())throw bad("在职员工信息不存在");return rows.get(0);}
  private long employeeStation(long user) {var rows=db.queryForList("select station_id from employee where user_id=? and status='ACTIVE'",Long.class,user);if(rows.isEmpty()||rows.get(0)==null)throw bad("请先配置所属服务站");return rows.get(0);}
  private void lockEmployee(long employee) {db.queryForMap("select id from employee where id=? for update",employee);}
  private Map<String,Object> locked(long id) {var employees=db.queryForList("select employee_id from leave_request where id=?",Long.class,id);if(employees.isEmpty())throw new BusinessException(404,"请假单不存在");lockEmployee(employees.get(0));return db.queryForMap("select * from leave_request where id=? for update",id);}
  private Map<String,Object> version(long id,int number) {var rows=db.queryForList("select * from leave_request_version where request_id=? and version_no=?",id,number);if(rows.isEmpty())throw new BusinessException(404,"请假版本不存在");return rows.get(0);}
  private Integer pendingVersion(long id) {var rows=db.queryForList("select version_no from leave_request_version where request_id=? and status='PENDING' order by version_no desc limit 1",Integer.class,id);return rows.isEmpty()?null:rows.get(0);}
  private void owner(Map<String,Object> row,CurrentUser user) {if (!user.id().equals(db.queryForObject("select user_id from employee where id=?",Long.class,row.get("employee_id")))) throw new BusinessException(403,"仅请假人可操作");}
  private void action(long id,Integer version,long actor,String action,String comment,LocalDateTime at,String location) {db.update("insert into leave_action(request_id,version_no,actor_user_id,action,comment,actual_return_at,actual_return_location) values(?,?,?,?,?,?,?)",id,version,actor,action,comment,at,location);audit.log("LEAVE_"+action,"LEAVE_REQUEST",id,null,Map.of("version",version,"action",action));}
  private LocalDateTime local(Object value) {return value instanceof Timestamp t?t.toLocalDateTime():(LocalDateTime)value;}
  private LocalDateTime now(){return LocalDateTime.now(ZONE);}
  private void requireVersion(Map<String,Object> row,Integer expected){if(expected==null||((Number)row.get("current_version")).intValue()!=expected)throw new BusinessException(409,"请假版本已变化，请刷新后重试");}
  private String hash(String text){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
  private boolean blank(String value) {return value==null||value.isBlank();}
  private BusinessException bad(String message) {return new BusinessException(400,message);}
}
