package com.talent.platform.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SpaControllerTest {
  @ParameterizedTest
  @ValueSource(strings={"/courses/sessions","/courses/manage","/courses/materials","/courses/my","/courses/learning"})
  void courseRoutesForwardToTheSpaWhenOpenedOrRefreshed(String route) throws Exception {
    MockMvcBuilders.standaloneSetup(new SpaController()).build().perform(get(route))
        .andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
  }

  @Test void courseApiRequestsAreNotForwardedToHtml() throws Exception {
    MockMvcBuilders.standaloneSetup(new SpaController()).build().perform(get("/api/v1/courses"))
        .andExpect(status().isNotFound());
  }
}
