package com.talent.platform.station;

import com.talent.platform.common.*;
import com.talent.platform.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/station-change-requests")
public class StationChangeRequestController {
    private final JdbcTemplate db;
    private final AuditService audit;
    private final PermissionService permissions;

    public StationChangeRequestController(JdbcTemplate db, AuditService audit, PermissionService permissions) {
        this.db = db;
        this.audit = audit;
        this.permissions = permissions;
    }

    public record SubmitRequest(@NotNull Long stationId) {}
    public record ReviewRequest(@Size(max = 255) String comment) {}
    public record ReviewSummary(
            long total,
            long pending,
            long approvedToday,
            long rejectedToday,
            double averagePendingHours) {}

    /** 员工提交变更申请 */
    @PostMapping
    @Transactional
    public ApiResponse<Long> submit(@Valid @RequestBody SubmitRequest q) {
        var u = SecurityUtils.current();
        if (!"EMPLOYEE".equals(u.role()))
            throw new BusinessException(403, "仅员工可提交服务站变更申请");
        var employees = db.queryForList(
                "select id,station_id from employee where user_id=? and status='ACTIVE' for update", u.id());
        if (employees.isEmpty()) throw new BusinessException(404, "在职员工信息不存在");
        var employee = employees.get(0);
        Long eid = ((Number) employee.get("id")).longValue();
        Long currentStationId = employee.get("station_id") == null ? null : ((Number) employee.get("station_id")).longValue();
        Integer stationExists = db.queryForObject(
                "select count(*) from service_station where id=? and enabled=true", Integer.class, q.stationId());
        if (stationExists == null || stationExists == 0)
            throw new BusinessException(400, "目标服务站不存在或已停用");

        // 检查是否有待审批的申请
        var pending = db.queryForList("select id from station_change_request where employee_id=? and status='PENDING'", eid);
        if (!pending.isEmpty())
            throw new BusinessException(400, "您已有一个待审批的申请，不能重复提交");

        // 检查是否与当前服务站相同
        if (Objects.equals(currentStationId, q.stationId()))
            throw new BusinessException(400, "申请的服务站与当前相同");

        db.update("insert into station_change_request(employee_id,current_station_id,requested_station_id) values(?,?,?)",
                eid, currentStationId, q.stationId());
        Long id = db.queryForObject("select last_insert_id()", Long.class);
        audit.log("SUBMIT_STATION_CHANGE", "EMPLOYEE", eid, null, Map.of("requestId", id, "stationId", q.stationId()));
        return ApiResponse.ok(id);
    }

    /** 获取申请列表：admin 传 ?status=PENDING，employee 传 ?mine=true */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false, defaultValue = "false") boolean mine) {
        var u = SecurityUtils.current();

        if (mine) {
            // 员工查看自己的申请
            if (!"EMPLOYEE".equals(u.role()))
                throw new BusinessException(403, "仅员工可查看自己的申请");
            var employee = db.queryForList("select id from employee where user_id=?", u.id());
            if (employee.isEmpty()) throw new BusinessException(404, "员工信息不存在");
            Long eid = ((Number) employee.get(0).get("id")).longValue();
            return ApiResponse.ok(db.queryForList("""
                select r.*, s.name requested_station_name, cs.name current_station_name,
                       r2.display_name reviewer_name
                from station_change_request r
                left join service_station s on s.id=r.requested_station_id
                left join service_station cs on cs.id=r.current_station_id
                left join sys_user r2 on r2.id=r.reviewed_by
                where r.employee_id=?
                order by r.created_at desc
                """, eid));
        }

        // 管理员查看
        permissions.require(Permissions.MASTER_MANAGE);
        if (status != null && !status.isBlank()
                && !Set.of("PENDING", "APPROVED", "REJECTED").contains(status)) {
            throw new BusinessException(400, "审批状态不正确");
        }
        var sql = new StringBuilder("""
            select r.*, s.name requested_station_name, cs.name current_station_name,
                   e.name employee_name, e.employee_no,e.status employee_status,
                   eu.avatar_token,
                   b.name batch_name,bu.name business_unit_name,
                   tm.display_name mentor_name,sm.display_name skill_mentor_name,
                   r2.display_name reviewer_name,
                   timestampdiff(hour,r.created_at,coalesce(r.reviewed_at,now())) waiting_hours,
                   (select count(*) from station_change_request history
                    where history.employee_id=e.id and history.status='APPROVED') approved_change_count
            from station_change_request r
            join employee e on e.id=r.employee_id
            join sys_user eu on eu.id=e.user_id
            left join service_station s on s.id=r.requested_station_id
            left join service_station cs on cs.id=r.current_station_id
            left join talent_batch b on b.id=e.batch_id
            left join business_unit bu on bu.id=e.business_unit_id
            left join sys_user tm on tm.id=e.mentor_user_id
            left join sys_user sm on sm.id=e.skill_mentor_user_id
            left join sys_user r2 on r2.id=r.reviewed_by
            """);
        var where = new ArrayList<String>();
        var args = new ArrayList<Object>();
        if (status != null && !status.isBlank()) {
            where.add("r.status=?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.add("(e.name like ? or e.employee_no like ?)");
            var value = "%" + keyword.trim() + "%";
            args.add(value);
            args.add(value);
        }
        if (stationId != null) {
            where.add("(r.current_station_id=? or r.requested_station_id=?)");
            args.add(stationId);
            args.add(stationId);
        }
        if (dateFrom != null) {
            where.add("r.created_at>=?");
            args.add(dateFrom.atStartOfDay());
        }
        if (dateTo != null) {
            where.add("r.created_at<?");
            args.add(dateTo.plusDays(1).atStartOfDay());
        }
        if (!where.isEmpty()) sql.append(" where ").append(String.join(" and ", where));
        sql.append("""
             order by case when r.status='PENDING' then 0 else 1 end,
                      case when r.status='PENDING' then r.created_at end asc,
                      r.created_at desc
            """);
        return ApiResponse.ok(db.queryForList(sql.toString(), args.toArray()));
    }

    @GetMapping("/summary")
    public ApiResponse<ReviewSummary> summary() {
        permissions.require(Permissions.MASTER_MANAGE);
        var row = db.queryForMap("""
            select count(*) total,
                   coalesce(sum(status='PENDING'),0) pending,
                   coalesce(sum(status='APPROVED' and date(reviewed_at)=current_date),0) approved_today,
                   coalesce(sum(status='REJECTED' and date(reviewed_at)=current_date),0) rejected_today,
                   coalesce(avg(case when status='PENDING'
                     then timestampdiff(hour,created_at,now()) end),0) average_pending_hours
            from station_change_request
            """);
        return ApiResponse.ok(new ReviewSummary(
                number(row.get("total")),
                number(row.get("pending")),
                number(row.get("approved_today")),
                number(row.get("rejected_today")),
                decimal(row.get("average_pending_hours"))));
    }

    /** 管理员审批通过 */
    @PutMapping("/{id}/approve")
    @Transactional
    public ApiResponse<Void> approve(@PathVariable Long id, @RequestBody(required = false) ReviewRequest q) {
        permissions.require(Permissions.MASTER_MANAGE);
        var u = SecurityUtils.current();
        var rows = db.queryForList("select * from station_change_request where id=? for update", id);
        if (rows.isEmpty()) throw new BusinessException(404, "服务站变更申请不存在");
        var row = rows.get(0);
        if (!"PENDING".equals(String.valueOf(row.get("status"))))
            throw new BusinessException(400, "该申请已处理");

        Long employeeId = ((Number) row.get("employee_id")).longValue();
        Long requestedStationId = ((Number) row.get("requested_station_id")).longValue();
        Long requestedFromStationId = nullableLong(row.get("current_station_id"));
        var employees = db.queryForList(
                "select station_id from employee where id=? and status='ACTIVE' for update",
                employeeId);
        if (employees.isEmpty()) throw new BusinessException(400, "员工已不在职，不能通过该申请");
        var actualStationId = nullableLong(employees.get(0).get("station_id"));
        if (!Objects.equals(requestedFromStationId, actualStationId)) {
            throw new BusinessException(409, "员工当前服务站已发生变化，请让员工重新提交申请");
        }
        var targetAvailable = db.queryForObject(
                "select count(*) from service_station where id=? and enabled=true",
                Integer.class,
                requestedStationId);
        if (targetAvailable == null || targetAvailable == 0) {
            throw new BusinessException(400, "目标服务站已停用，不能通过该申请");
        }

        db.update("update employee set station_id=?,version=version+1 where id=?", requestedStationId, employeeId);
        db.update("update station_change_request set status='APPROVED',reviewed_by=?,review_comment=?,reviewed_at=now(),updated_at=now() where id=?",
                u.id(), q != null ? q.comment() : null, id);
        audit.log("APPROVE_STATION_CHANGE", "EMPLOYEE", employeeId, row, Map.of("requestId", id));
        return ApiResponse.ok(null);
    }

    /** 管理员审批拒绝 */
    @PutMapping("/{id}/reject")
    @Transactional
    public ApiResponse<Void> reject(@PathVariable Long id, @RequestBody(required = false) ReviewRequest q) {
        permissions.require(Permissions.MASTER_MANAGE);
        var u = SecurityUtils.current();
        if (q == null || q.comment() == null || q.comment().isBlank()) {
            throw new BusinessException(400, "拒绝申请时必须填写原因");
        }
        var rows = db.queryForList("select * from station_change_request where id=? for update", id);
        if (rows.isEmpty()) throw new BusinessException(404, "服务站变更申请不存在");
        var row = rows.get(0);
        if (!"PENDING".equals(String.valueOf(row.get("status"))))
            throw new BusinessException(400, "该申请已处理");

        db.update("update station_change_request set status='REJECTED',reviewed_by=?,review_comment=?,reviewed_at=now(),updated_at=now() where id=?",
                u.id(), q != null ? q.comment() : null, id);
        audit.log("REJECT_STATION_CHANGE", "EMPLOYEE", row.get("employee_id"), row, Map.of("requestId", id));
        return ApiResponse.ok(null);
    }

    /** 按人员数据范围查询已生效的服务站变更记录 */
    @GetMapping("/employee/{employeeId}")
    public ApiResponse<List<Map<String, Object>>> employeeHistory(@PathVariable Long employeeId) {
        permissions.require(Permissions.EMPLOYEE_READ);
        permissions.requireEmployee(employeeId);
        var sql = """
            select r.id,r.employee_id,r.current_station_id,r.requested_station_id,
                   r.review_comment,r.created_at request_at,
                   coalesce(r.reviewed_at,r.updated_at) effective_at,
                   s.name requested_station_name,cs.name current_station_name,
                   r2.display_name reviewer_name
            from station_change_request r
            left join service_station s on s.id=r.requested_station_id
            left join service_station cs on cs.id=r.current_station_id
            left join sys_user r2 on r2.id=r.reviewed_by
            where r.employee_id=? and r.status='APPROVED'
            order by coalesce(r.reviewed_at,r.updated_at) desc,r.id desc
            """;
        return ApiResponse.ok(db.queryForList(sql, employeeId));
    }

    private long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private double decimal(Object value) {
        return value == null ? 0 : ((Number) value).doubleValue();
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
