package com.godenglow.seckill.controller;

import com.godenglow.seckill.result.Result;
import com.godenglow.seckill.service.SeckillUserService;
import com.godenglow.seckill.vo.LoginVO;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

@Controller
@RequestMapping("/login")
@Slf4j
public class LoginController {

    @Autowired
    private SeckillUserService seckillUserService;

    @ApiOperation("获取登录界面接口")
    @RequestMapping("/to_login")
    public String toLogin() {
        return "login";
    }

    @ApiOperation("登录接口")
    @ApiImplicitParam(name = "loginVO", value = "登录实体", required = true, dataType = "LoginVO")
    @RequestMapping("/do_login")
    @ResponseBody
    public Result<String> doLogin(HttpServletResponse response, @Valid LoginVO loginVO) {
        //只记录手机号，不要打印整个 LoginVO —— 它的 toString() 里含 password 明文（客户端摘要）
        log.info("【用户登录】mobile=" + loginVO.getMobile());

        //登录
        String token = seckillUserService.login(response, loginVO);
        return Result.success(token);
    }
}
