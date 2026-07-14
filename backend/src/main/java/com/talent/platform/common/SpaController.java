package com.talent.platform.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
  @GetMapping("/{path:[a-zA-Z0-9_-]+}")
  public String spa(){return "forward:/index.html";}
}
