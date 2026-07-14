package com.talent.platform.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityUtilsTest {
  @Test
  void trainingManagerIncludesTrainingAndSystemAdminsButNotMentors(){
    assertTrue(SecurityUtils.trainingManager(user("TRAINING_ADMIN")));
    assertTrue(SecurityUtils.trainingManager(user("ADMIN")));
    assertTrue(SecurityUtils.trainingManager(user("SUPER_ADMIN")));
    assertFalse(SecurityUtils.trainingManager(user("MENTOR")));
    assertFalse(SecurityUtils.trainingManager(user("EMPLOYEE")));
  }

  private CurrentUser user(String role){return new CurrentUser(1L,"user","User",role,false);}
}
