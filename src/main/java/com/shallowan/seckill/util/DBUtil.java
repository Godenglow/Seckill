package com.shallowan.seckill.util;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * @author ShallowAn
 */
public class DBUtil {

    private static Properties props = new Properties();

    static {
        // 数据库连接信息位于 application-local.properties（含凭据，已被 .gitignore 忽略），
        // 公共配置位于 application.properties。按顺序加载，后加载的同名键覆盖先加载的。
        load("application.properties");
        load("application-local.properties");
    }

    private static void load(String resource) {
        try (InputStream in = DBUtil.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Connection getConn() throws Exception {
        String url = props.getProperty("spring.datasource.url");
        String username = props.getProperty("spring.datasource.username");
        String password = props.getProperty("spring.datasource.password");
        String driver = props.getProperty("spring.datasource.driver-class-name");
        Class.forName(driver);
        return DriverManager.getConnection(url, username, password);
    }
}
