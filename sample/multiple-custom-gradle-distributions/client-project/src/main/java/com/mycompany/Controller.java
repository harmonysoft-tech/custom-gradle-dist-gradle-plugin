package com.mycompany;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Controller {

    @RequestMapping("/ping")
    public String ping() {
        return "Hi there!\n";
    }
}
