/**
 * <b>严肃声明：</b><br/>
 * 开源版本请务必保留此注释头信息，若删除我方将保留所有法律责任追究！<br/>
 * 本系统已申请软件著作权，受国家版权局知识产权以及国家计算机软件著作权保护！<br/>
 * 可正常分享和学习源码，不得用于违法犯罪活动，违者必究！<br/>
 * Copyright (c) 2019-2020 十三 all rights reserved.<br/>
 * 版权所有，侵权必究！
 */
package ltd.newbee.mall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * newbee-mall 商城项目启动类
 *
 * @author 13
 * @qq交流群 796794009
 * @email 2449207463@qq.com
 * @link https://github.com/newbee-ltd
 */
@SpringBootApplication // Spring Boot 主注解，启用自动配置和组件扫描
@MapperScan("ltd.newbee.mall.dao") // 指定 MyBatis Mapper 接口的扫描路径
@EnableScheduling // 开启定时任务(订单超时自动关闭)
public class NewBeeMallApplication {

    /**
     * 程序入口方法
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(NewBeeMallApplication.class, args); // 启动 Spring Boot 应用
    }
}
