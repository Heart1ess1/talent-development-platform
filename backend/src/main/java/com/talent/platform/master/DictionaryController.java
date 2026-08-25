package com.talent.platform.master;

import com.talent.platform.common.ApiResponse;
import com.talent.platform.common.BusinessException;
import com.talent.platform.security.AuditService;
import com.talent.platform.security.PermissionService;
import com.talent.platform.security.Permissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dictionaries")
public class DictionaryController {
  private static final Map<String, TypeDefinition> TYPES = types();

  private final JdbcTemplate db;
  private final PermissionService permissions;
  private final AuditService audit;

  public DictionaryController(JdbcTemplate db, PermissionService permissions, AuditService audit) {
    this.db = db;
    this.permissions = permissions;
    this.audit = audit;
  }

  public record ValueRequest(
      @Size(max = 64) String value,
      @NotBlank @Size(max = 64) String label,
      @Min(-9999) @Max(9999) Integer sortOrder,
      Boolean enabled) {}

  public record DictionaryItemView(
      Long id,
      String value,
      String label,
      int sortOrder,
      boolean enabled) {}

  public record DictionaryTypeView(
      String code,
      String name,
      String description,
      boolean coded,
      List<DictionaryItemView> items) {}

  @GetMapping
  public ApiResponse<List<DictionaryTypeView>> types(
      @RequestParam(defaultValue = "false") boolean includeDisabled) {
    if (includeDisabled) permissions.require(Permissions.MASTER_MANAGE);
    var result = new ArrayList<DictionaryTypeView>();
    TYPES.values().forEach(type -> result.add(view(type, includeDisabled)));
    return ApiResponse.ok(result);
  }

  @GetMapping("/{typeCode}/values")
  public ApiResponse<List<DictionaryItemView>> values(
      @PathVariable String typeCode,
      @RequestParam(defaultValue = "false") boolean includeDisabled) {
    if (includeDisabled) permissions.require(Permissions.MASTER_MANAGE);
    return ApiResponse.ok(items(definition(typeCode), includeDisabled));
  }

  @PostMapping("/{typeCode}/values")
  @Transactional
  public ApiResponse<Long> create(
      @PathVariable String typeCode,
      @Valid @RequestBody ValueRequest request) {
    permissions.require(Permissions.MASTER_MANAGE);
    var type = definition(typeCode);
    String label = request.label().trim();
    int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
    boolean enabled = request.enabled() == null || request.enabled();
    if (type.table() != null) {
      db.update("insert into " + type.table() + "(name,sort_order,enabled) values(?,?,?)",
          label, sortOrder, enabled);
    } else {
      String value = requiredValue(request.value());
      db.update("insert into dictionary_item(type_code,item_value,label,sort_order,enabled) values(?,?,?,?,?)",
          type.code(), value, label, sortOrder, enabled);
    }
    Long id = db.queryForObject("select last_insert_id()", Long.class);
    audit.log("CREATE_DICTIONARY_VALUE", "DICTIONARY_VALUE", id, null,
        Map.of("typeCode", type.code(), "value", request.value() == null ? label : request.value(),
            "label", label, "sortOrder", sortOrder, "enabled", enabled));
    return ApiResponse.ok(id);
  }

  @PutMapping("/{typeCode}/values/{id}")
  @Transactional
  public ApiResponse<Void> update(
      @PathVariable String typeCode,
      @PathVariable Long id,
      @Valid @RequestBody ValueRequest request) {
    permissions.require(Permissions.MASTER_MANAGE);
    var type = definition(typeCode);
    var before = find(type, id);
    String label = request.label().trim();
    int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
    boolean enabled = request.enabled() == null || request.enabled();
    if (type.table() != null) {
      db.update("update " + type.table() + " set name=?,sort_order=?,enabled=? where id=?",
          label, sortOrder, enabled, id);
    } else {
      String value = requiredValue(request.value());
      if (!value.equals(String.valueOf(before.get("item_value")))) {
        throw new BusinessException(400, "字典保存值创建后不可修改");
      }
      db.update("update dictionary_item set label=?,sort_order=?,enabled=? where id=? and type_code=?",
          label, sortOrder, enabled, id, type.code());
    }
    audit.log("UPDATE_DICTIONARY_VALUE", "DICTIONARY_VALUE", id, before,
        Map.of("typeCode", type.code(), "value", String.valueOf(before.get("item_value")),
            "label", label, "sortOrder", sortOrder, "enabled", enabled));
    return ApiResponse.ok(null);
  }

  private DictionaryTypeView view(TypeDefinition type, boolean includeDisabled) {
    return new DictionaryTypeView(
        type.code(), type.name(), type.description(), type.table() == null,
        items(type, includeDisabled));
  }

  private List<DictionaryItemView> items(TypeDefinition type, boolean includeDisabled) {
    String enabledFilter = includeDisabled ? "" : " and enabled=true";
    List<Map<String, Object>> rows;
    if (type.table() != null) {
      rows = db.queryForList("select id,cast(id as char) item_value,name label,sort_order,enabled from "
          + type.table() + " where 1=1" + enabledFilter + " order by sort_order,name,id");
    } else {
      rows = db.queryForList("select id,item_value,label,sort_order,enabled from dictionary_item "
          + "where type_code=?" + enabledFilter + " order by sort_order,label,id", type.code());
    }
    return rows.stream().map(this::item).toList();
  }

  private Map<String, Object> find(TypeDefinition type, Long id) {
    List<Map<String, Object>> rows;
    if (type.table() != null) {
      rows = db.queryForList("select id,cast(id as char) item_value,name label,sort_order,enabled from "
          + type.table() + " where id=? for update", id);
    } else {
      rows = db.queryForList("select id,item_value,label,sort_order,enabled from dictionary_item "
          + "where id=? and type_code=? for update", id, type.code());
    }
    if (rows.isEmpty()) throw new BusinessException(404, "字典值不存在");
    return rows.get(0);
  }

  private DictionaryItemView item(Map<String, Object> row) {
    return new DictionaryItemView(
        ((Number) row.get("id")).longValue(),
        String.valueOf(row.get("item_value")),
        String.valueOf(row.get("label")),
        ((Number) row.getOrDefault("sort_order", 0)).intValue(),
        truthy(row.get("enabled")));
  }

  private TypeDefinition definition(String typeCode) {
    var type = TYPES.get(typeCode.toUpperCase(Locale.ROOT));
    if (type == null) throw new BusinessException(404, "字典类型不存在");
    return type;
  }

  private String requiredValue(String value) {
    if (value == null || value.isBlank()) throw new BusinessException(400, "字典保存值不能为空");
    return value.trim();
  }

  private boolean truthy(Object value) {
    return Boolean.TRUE.equals(value)
        || value instanceof Number number && number.intValue() == 1
        || "1".equals(String.valueOf(value));
  }

  private static Map<String, TypeDefinition> types() {
    var values = new LinkedHashMap<String, TypeDefinition>();
    register(values, new TypeDefinition("BATCH", "培养批次", "用于人员、任务、考试与评价范围", "talent_batch"));
    register(values, new TypeDefinition("CLASS", "班级", "用于人员归班和各业务场景的人员筛选", null));
    register(values, new TypeDefinition("CLASS_POSITION", "班级职务", "用于人员班级职责和各业务场景的人员筛选", null));
    register(values, new TypeDefinition("BUSINESS_UNIT", "所属板块", "用于人员组织归属与业务范围", "business_unit"));
    register(values, new TypeDefinition("SERVICE_STATION", "服务站点", "用于人员归属、调站与数据权限", "service_station"));
    register(values, new TypeDefinition("EDUCATION", "学历", "用于人员档案和人员筛选", null));
    register(values, new TypeDefinition("POLITICAL_STATUS", "政治面貌", "用于人员档案", null));
    register(values, new TypeDefinition("SESSION_NAME", "场次名称", "用于培训场次名称选择", null));
    register(values, new TypeDefinition("TRAINING_LOCATION", "培训地点", "用于线下培训地点选择", null));
    return Collections.unmodifiableMap(values);
  }

  private static void register(Map<String, TypeDefinition> values, TypeDefinition type) {
    values.put(type.code(), type);
  }

  private record TypeDefinition(String code, String name, String description, String table) {}
}
