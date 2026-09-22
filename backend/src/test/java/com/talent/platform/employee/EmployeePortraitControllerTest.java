package com.talent.platform.employee;

import com.talent.platform.security.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class EmployeePortraitControllerTest {
  @Test void everyEndpointChecksPortraitPermissionAndEmployeeScope(){
    var service=mock(EmployeePortraitService.class);var permissions=mock(PermissionService.class);
    var controller=new EmployeePortraitController(service,permissions);
    controller.overview(9L);controller.courses(9L,"SESSIONS",1,20,null,null);controller.tasks(9L,null,1,20,null,null);
    controller.exams(9L,1,20,null,null);controller.evaluations(9L,"MONTH",1,20,null,null);controller.stations(9L);
    controller.locations(9L,1,20,null,null);controller.timeline(9L,null,1,20,null,null);
    verify(permissions,times(8)).require(Permissions.EMPLOYEE_PORTRAIT_VIEW);
    verify(permissions,times(8)).requireEmployee(9L);
  }
}
