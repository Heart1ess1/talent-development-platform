package com.talent.platform.common;

import com.talent.platform.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/organization-folders/{domain}")
public class OrganizationFolderController {
  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final AuditService audit;

  public OrganizationFolderController(JdbcTemplate db, PermissionService permissions, AuditService audit) {
    this.db=db; this.permissions=permissions; this.audit=audit;
  }
  public record FolderRequest(@NotBlank @Size(max=80) String name) {}
  public record MoveRequest(@NotEmpty @Size(max=500) List<@NotNull @Positive Long> itemIds, @Positive Long folderId) {}
  private record Domain(String folders, String items, String permission) {}

  private Domain domain(String name) {
    var result=switch(name) {
      case "training-plans" -> new Domain("training_plan_folder","training_plan",Permissions.TASK_MANAGE);
      case "question-banks" -> new Domain("question_bank_folder","exam_question_bank",Permissions.EXAM_MANAGE);
      default -> throw new BusinessException(400,"不支持的文件夹类型");
    };
    permissions.require(result.permission());
    return result;
  }

  @GetMapping
  public ApiResponse<List<Map<String,Object>>> list(@PathVariable String domain) {
    var d=domain(domain);
    return ApiResponse.ok(db.queryForList("select f.id,f.name,(select count(*) from "+d.items()+
        " i where i.folder_id=f.id) item_count from "+d.folders()+" f order by f.name,f.id"));
  }

  @PostMapping
  @Transactional
  public ApiResponse<Long> create(@PathVariable String domain,@Valid @RequestBody FolderRequest request) {
    var d=domain(domain);
    try {
      db.update("insert into "+d.folders()+"(name,created_by) values(?,?)",request.name().trim(),SecurityUtils.current().id());
    } catch(DuplicateKeyException e) { throw new BusinessException(400,"文件夹名称已存在"); }
    Long id=db.queryForObject("select last_insert_id()",Long.class);
    audit.log("CREATE_FOLDER",d.folders(),id,null,request);
    return ApiResponse.ok(id);
  }

  @PutMapping("/{id}")
  @Transactional
  public ApiResponse<Void> rename(@PathVariable String domain,@PathVariable Long id,@Valid @RequestBody FolderRequest request) {
    var d=domain(domain);
    var before=folder(d,id);
    try { db.update("update "+d.folders()+" set name=? where id=?",request.name().trim(),id); }
    catch(DuplicateKeyException e) { throw new BusinessException(400,"文件夹名称已存在"); }
    audit.log("RENAME_FOLDER",d.folders(),id,before,request);
    return ApiResponse.ok(null);
  }

  @DeleteMapping("/{id}")
  @Transactional
  public ApiResponse<Void> delete(@PathVariable String domain,@PathVariable Long id) {
    var d=domain(domain);
    var before=folder(d,id);
    // Foreign keys return items to Uncategorized; business records are never deleted here.
    db.update("delete from "+d.folders()+" where id=?",id);
    audit.log("DELETE_FOLDER",d.folders(),id,before,null);
    return ApiResponse.ok(null);
  }

  @PutMapping("/items")
  @Transactional
  public ApiResponse<Void> move(@PathVariable String domain,@Valid @RequestBody MoveRequest request) {
    var d=domain(domain);
    if(request.folderId()!=null)folder(d,request.folderId());
    var ids=new ArrayList<>(new TreeSet<>(request.itemIds()));
    String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
    var before=db.queryForList("select id,folder_id from "+d.items()+" where id in ("+marks+") order by id for update",ids.toArray());
    if(before.size()!=ids.size())throw new BusinessException(404,"部分内容已不存在，请刷新后重试");
    var args=new ArrayList<Object>(); args.add(request.folderId()); args.addAll(ids);
    db.update("update "+d.items()+" set folder_id=? where id in ("+marks+")",args.toArray());
    audit.log("MOVE_TO_FOLDER",d.items(),null,before,request);
    return ApiResponse.ok(null);
  }

  private Map<String,Object> folder(Domain d,Long id) {
    var rows=db.queryForList("select id,name from "+d.folders()+" where id=? for update",id);
    if(rows.isEmpty())throw new BusinessException(404,"文件夹不存在，请刷新后重试");
    return rows.get(0);
  }
}
