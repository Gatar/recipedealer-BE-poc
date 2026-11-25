package com.gatar.recipedealer.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The admin dashboard: static files in {@code static/admin/}, behind the admin login (see {@code SecurityConfig}). */
@Controller
public class DashboardPageController {

    @GetMapping({"/admin", "/admin/"})
    public String dashboard() {
        return "forward:/admin/index.html";
    }
}
