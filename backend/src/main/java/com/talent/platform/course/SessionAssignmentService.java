package com.talent.platform.course;

import com.talent.platform.common.BusinessException;
import com.talent.platform.common.PageResult;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import com.talent.platform.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
public class SessionAssignmentService {
  // Only fields needed for arranging training. Do not expose the employee ledger's PII.
  private static final String PERSON_SELECT = """
      select e.id employee_id,e.employee_no,e.name employee_name,e.status,
        e.batch_id,e.class_id,e.class_position_id,e.business_unit_id,e.station_id,e.mentor_user_id,e.skill_mentor_user_id,
        b.name batch_name,cls.label class_name,cp.label class_position_name,
        bu.name business_unit_name,s.name station_name,
        tm.display_name technical_mentor_name,sm.display_name skill_mentor_name,
        e.education,e.school,e.major
      """;
  private static final String PERSON_FROM = """
       from employee e
       left join talent_batch b on b.id=e.batch_id
       left join dictionary_item cls on cls.id=e.class_id and cls.type_code='CLASS'
       left join dictionary_item cp on cp.id=e.class_position_id and cp.type_code='CLASS_POSITION'
       left join business_unit bu on bu.id=e.business_unit_id
       left join service_station s on s.id=e.station_id
       left join sys_user tm on tm.id=e.mentor_user_id
       left join sys_user sm on sm.id=e.skill_mentor_user_id
      """;

  public record Filters(String keyword, List<Long> batchIds, List<Long> classIds,
      List<Long> classPositionIds, List<Long> businessUnitIds, List<Long> stationIds,
      List<Long> mentorIds, List<Long> skillMentorIds, List<String> educations,
      String school, String major) {}
  public record Changes(@NotNull List<@NotNull Long> addIds,
      @NotNull List<@NotNull Long> removeIds, @NotBlank String baselineHash) {}
  public record State(List<Map<String,Object>> records, String baselineHash, int enrolledCount, long attendanceCount) {}
  public record ChangeResult(int added, int removed, State state) {}
  record Query(String sql, List<Object> args) {}

  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final AuditService audit;

  public SessionAssignmentService(JdbcTemplate db, PermissionService permissions, AuditService audit) {
    this.db=db; this.permissions=permissions; this.audit=audit;
  }

  public void lockSession(Long id) {
    if (db.queryForList("select id from course_session where id=? for update", id).isEmpty())
      throw new BusinessException(404,"培训场次不存在或已被删除");
  }

  private void requireSession(Long id) {
    permissions.require(Permissions.COURSE_MANAGE);
    if (db.queryForList("select id from course_session where id=?", id).isEmpty())
      throw new BusinessException(404,"培训场次不存在或已被删除");
  }

  public State state(Long id) {
    requireSession(id);
    return readState(id);
  }

  private State readState(Long id) {
    var scope=permissions.employeeFilter("e");
    var args=new ArrayList<Object>(); args.add(id); args.addAll(scope.args());
    var rows=db.queryForList(PERSON_SELECT + ",ce.assigned_at,a.status attendance_status,a.checked_at "
        + PERSON_FROM + """
         join course_enrollment ce on ce.employee_id=e.id
         left join attendance a on a.session_id=ce.session_id and a.employee_id=e.id
         where ce.session_id=?
        """ + scope.sql() + " order by e.employee_no,e.id", args.toArray());
    return new State(rows, fingerprint(rows), rows.size(), rows.stream().filter(r->r.get("attendance_status")!=null).count());
  }

  static String fingerprint(List<Map<String,Object>> rows) {
    try {
      var digest=MessageDigest.getInstance("SHA-256");
      rows.stream().sorted(Comparator.comparingLong(r->((Number)r.get("employee_id")).longValue())).forEach(r->{
        for (String key:List.of("employee_id","assigned_at","attendance_status","checked_at")) {
          digest.update(String.valueOf(r.get(key)).getBytes(StandardCharsets.UTF_8));
          digest.update((byte)0);
        }
      });
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
  }

  Query candidateQuery(Long id, Filters filters) {
    var scope=permissions.employeeFilter("e");
    var where=new StringBuilder(" where e.status='ACTIVE' and not exists(select 1 from course_enrollment ce where ce.session_id=? and ce.employee_id=e.id)");
    var args=new ArrayList<Object>(); args.add(id);
    where.append(scope.sql()); args.addAll(scope.args());
    if (filters!=null) {
      if (filters.keyword()!=null&&!filters.keyword().isBlank()) {
        where.append(" and (e.name like ? escape '!' or e.employee_no like ? escape '!')");
        args.add(pattern(filters.keyword())); args.add(pattern(filters.keyword()));
      }
      in(where,args,"e.batch_id",filters.batchIds());
      in(where,args,"e.class_id",filters.classIds());
      in(where,args,"e.class_position_id",filters.classPositionIds());
      in(where,args,"e.business_unit_id",filters.businessUnitIds());
      in(where,args,"e.station_id",filters.stationIds());
      in(where,args,"e.mentor_user_id",filters.mentorIds());
      in(where,args,"e.skill_mentor_user_id",filters.skillMentorIds());
      in(where,args,"e.education",filters.educations());
      like(where,args,"e.school",filters.school());
      like(where,args,"e.major",filters.major());
    }
    return new Query(where.toString(),args);
  }

  private static void in(StringBuilder sql,List<Object> args,String column,List<?> values) {
    if (values==null||values.isEmpty()) return;
    if(values.stream().anyMatch(Objects::isNull)) throw new BusinessException(400,"筛选项不能为空");
    var unique=new LinkedHashSet<>(values);
    sql.append(" and ").append(column).append(" in (").append(marks(unique.size())).append(")");
    args.addAll(unique);
  }
  private static void like(StringBuilder sql,List<Object> args,String column,String value) {
    if(value!=null&&!value.isBlank()) {sql.append(" and ").append(column).append(" like ? escape '!'");args.add(pattern(value));}
  }
  private static String pattern(String value) {return "%"+value.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
  private static String marks(int count) { return String.join(",",Collections.nCopies(count,"?")); }

  @Transactional(readOnly=true)
  public PageResult<Map<String,Object>> candidates(Long id,Filters filters,int page,int size) {
    requireSession(id);
    var query=candidateQuery(id,filters);
    long total=Objects.requireNonNull(db.queryForObject("select count(*) from employee e"+query.sql(),Long.class,query.args().toArray()));
    int pageSize=Math.min(100,Math.max(1,size)), pageNo=Math.max(1,page);
    var args=new ArrayList<>(query.args()); args.add(pageSize); args.add((long)(pageNo-1)*pageSize);
    var rows=db.queryForList(PERSON_SELECT+PERSON_FROM+query.sql()+" order by e.employee_no,e.id limit ? offset ?",args.toArray());
    return new PageResult<>(rows,total,pageNo,pageSize);
  }

  @Transactional(readOnly=true)
  public List<Map<String,Object>> resolve(Long id,Filters filters) {
    requireSession(id);
    var query=candidateQuery(id,filters);
    return db.queryForList(PERSON_SELECT+PERSON_FROM+query.sql()+" order by e.employee_no,e.id",query.args().toArray());
  }

  @Transactional
  public ChangeResult change(Long id,Changes request) {
    permissions.require(Permissions.COURSE_MANAGE);
    lockSession(id);
    var before=readState(id);
    if(!before.baselineHash().equals(request.baselineHash()))
      throw new BusinessException(409,"本场人员或签到记录已变化，请核对最新名单后再次保存");
    var adds=new TreeSet<>(request.addIds()); var removes=new TreeSet<>(request.removeIds());
    if(!Collections.disjoint(adds,removes)) throw new BusinessException(400,"同一人员不能同时新增和移除");
    var current=new HashMap<Long,Map<String,Object>>();
    before.records().forEach(row->current.put(((Number)row.get("employee_id")).longValue(),row));
    // Lock affected employees after the session, in ID order. This also serializes employee deactivation.
    var affected=new TreeSet<>(adds); affected.addAll(removes);
    var employeeRows=new HashMap<Long,Map<String,Object>>();
    if(!affected.isEmpty()) {
      var rows=db.queryForList("select id,status from employee where id in ("+marks(affected.size())+") order by id for update",affected.toArray());
      rows.forEach(row->employeeRows.put(((Number)row.get("id")).longValue(),row));
    }
    for(Long employeeId:affected) {
      if(!employeeRows.containsKey(employeeId)) throw new BusinessException(400,"人员不存在，请刷新后重新选择");
      permissions.requireEmployee(employeeId);
    }
    for(Long employeeId:adds) {
      if(!"ACTIVE".equals(employeeRows.get(employeeId).get("status")))
        throw new BusinessException(400,"存在已停用人员，请撤销该人员的新增后重试");
    }
    for(Long employeeId:removes) {
      if(!current.containsKey(employeeId)) throw new BusinessException(409,"待移除人员不在最新名单中，请刷新后重试");
      if(current.get(employeeId).get("attendance_status")!=null)
        throw new BusinessException(400,"该员工已有签到记录，不能移除课程安排");
    }
    int added=0,removed=0;
    for(Long employeeId:removes) removed+=db.update("delete from course_enrollment where session_id=? and employee_id=?",id,employeeId);
    for(Long employeeId:adds) if(!current.containsKey(employeeId))
      added+=db.update("insert into course_enrollment(session_id,employee_id,assigned_by) values(?,?,?)",id,employeeId,SecurityUtils.current().id());
    var after=readState(id);
    audit.log("UPDATE_SESSION_ENROLLMENTS","SESSION",id,
        Map.of("employeeIds",current.keySet(),"count",before.enrolledCount()),
        Map.of("addIds",adds,"removeIds",removes,"added",added,"removed",removed,"count",after.enrolledCount()));
    return new ChangeResult(added,removed,after);
  }

  @Transactional
  public int addLegacy(Long id,List<Long> employeeIds) {
    permissions.require(Permissions.COURSE_MANAGE); lockSession(id);
    return change(id,new Changes(employeeIds,List.of(),readState(id).baselineHash())).added();
  }

  @Transactional
  public void removeLegacy(Long id,Long employeeId) {
    permissions.require(Permissions.COURSE_MANAGE); lockSession(id);
    change(id,new Changes(List.of(),List.of(employeeId),readState(id).baselineHash()));
  }
}
