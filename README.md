# tomcat
tomcat源码阅读

# 源码环境搭建

## 下载源码包
```apache-tomcat-8.5.54.tar.gz```

## 解压源码包
```apache-tomcat-8.5.54-src```

## 在项目跟目录下创建pom.xml文件
```$xslt
    定义好maven坐标和编译插件
    <artifactId>tomcat</artifactId>
    <groupId>org.apache.tomcat</groupId>
    <version>8.5.54</version>
    <name>tomcat</name>

    <modelVersion>4.0.0</modelVersion>

    <build>
        <pluginManagement><!-- lock down plugins versions to avoid using Maven defaults (may be moved to parent pom) -->
            <plugins>
                <!-- clean lifecycle, see https://maven.apache.org/ref/current/maven-core/lifecycles.html#clean_Lifecycle -->
                <plugin>
                    <artifactId>maven-clean-plugin</artifactId>
                    <version>3.1.0</version>
                </plugin>
                <!-- default lifecycle, jar packaging: see https://maven.apache.org/ref/current/maven-core/default-bindings.html#Plugin_bindings_for_jar_packaging -->
                <plugin>
                    <artifactId>maven-resources-plugin</artifactId>
                    <version>3.0.2</version>
                </plugin>

                <!--<plugin>-->
                <!--<artifactId>maven-source-plugin</artifactId>-->
                <!--<version>3.0.0</version>-->
                <!--</plugin>-->
                <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.8.0</version>
                </plugin>
                <plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>2.22.1</version>
                </plugin>
                <plugin>
                    <artifactId>maven-jar-plugin</artifactId>
                    <version>3.0.2</version>
                </plugin>
                <plugin>
                    <artifactId>maven-install-plugin</artifactId>
                    <version>2.5.2</version>
                </plugin>
                <plugin>
                    <artifactId>maven-deploy-plugin</artifactId>
                    <version>2.8.2</version>
                </plugin>
                <!-- site lifecycle, see https://maven.apache.org/ref/current/maven-core/lifecycles.html#site_Lifecycle -->
                <plugin>
                    <artifactId>maven-site-plugin</artifactId>
                    <version>3.7.1</version>
                </plugin>
            </plugins>
        </pluginManagement>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <source>7</source>
                    <target>7</target>
                </configuration>
            </plugin>
            <plugin>
                <artifactId>maven-source-plugin</artifactId>
                <version>3.0.0</version>
                <executions>
                    <execution>
                        <id>attach-sources</id>
                        <goals>
                            <goal>jar</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
```

# 将解压的tomcat源码复制到项目根目录下

# 将java目录配置成源码目录
```$xslt
打开idea的Project Structure
选择Modules
将Java目录配置成Source
```

# 配置一个启动类
```$xslt
选择Edit Configurations...
Main Class一栏设置为 org.apache.catalina.startup.Bootstrap
VM Options一栏设置为(如果发现一下配置没有该文件夹可以自己建一个空的，也可以不建)
    -Dcatalina.home={项目根目录绝对路径}/{tomcat解压目录}
    -Dcatalina.base={项目根目录绝对路径}/{tomcat解压目录}
    -Djava.endorsed.dirs={项目根目录绝对路径}/{tomcat解压目录}/endorsed
    -Djava.io.tmpdir={项目根目录绝对路径}/{tomcat解压目录}/temp
    -Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager
    -Djava.util.logging.config.file=/{项目根目录绝对路径}/{tomcat解压目录}/conf/logging.properties
```

# 配置ContextConfig.java
```$xslt
找到org.apache.catalina.startup.ContextConfig.configureStart方法
在webConfig();上面增加context.addServletContainerInitializer(new JasperInitializer(), null);
```

# 启动并打开http://localhost:8080验证