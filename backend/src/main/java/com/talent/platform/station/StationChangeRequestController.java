package com.talent.platform.station;

import com.talent.platform.common.*;
import com.talent.platform.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
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
        var sql = new StringBuilder("""
            select r.*, s.name requested_station_name, cs.name current_station_name,
                   e.name employee_name, e.employee_no,
                   r2.display_name reviewer_name
            from station_change_request r
            join employee e on e.id=r.employee_id
            left join service_station s on s.id=r.requested_station_id
            left join service_station cs on cs.id=r.current_station_id
            left join sys_user r2 on r2.id=r.reviewed_by
            """);
        var args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" where r.status=?");
            args.add(status);
        }
        sql.append(" order by r.created_at desc");
        return ApiResponse.ok(db.queryForList(sql.toString(), args.toArray()));
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

        db.update("update employee set station_id=?,version=version+1 where id=?", requestedStationId, employeeId);
        db.update("update station_change_request set status='APPROVED',reviewed_by=?,review_comment=?,updated_at=now() where id=?",
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
        var rows = db.queryForList("select * from station_change_request where id=? for update", id);
        if (rows.isEmpty()) throw new BusinessException(404, "服务站变更申请不存在");
        var row = rows.get(0);
        if (!"PENDING".equals(String.valueOf(row.get("status"))))
            throw new BusinessException(400, "该申请已处理");

        db.update("update station_change_request set status='REJECTED',reviewed_by=?,review_comment=?,updated_at=now() where id=?",
                u.id(), q != null ? q.comment() : null, id);
        audit.log("REJECT_STATION_CHANGE", "EMPLOYEE", row.get("employee_id"), row, Map.of("requestId", id));
        return ApiResponse.ok(null);
    }

    /** 获取某员工的历史变更记录（管理员用） */
    @GetMapping("/employee/{employeeId}")
    public ApiResponse<List<Map<String, Object>>> employeeHistory(@PathVariable Long employeeId) {
        permissions.require(Permissions.MASTER_MANAGE);
        var sql = """
            select r.*, s.name requested_station_name, cs.name current_station_name,
                   r2.display_name reviewer_name
            from station_change_request r
            left join service_station s on s.id=r.requested_station_id
            left join service_station cs on cs.id=r.current_station_id
            left join sys_user r2 on r2.id=r.reviewed_by
            where r.employee_id=? and r.status='APPROVED'
            order by r.created_at desc
            """;
        return ApiResponse.ok(db.queryForList(sql, employeeId));
    }
}
