# ---------- 阶段1：编译 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# 先拷 pom（含各模块），利用 Docker 层缓存：只改代码时不重复下载依赖
COPY pom.xml .
COPY backend/commons/pom.xml            backend/commons/
COPY backend/manager_agent/pom.xml      backend/manager_agent/
COPY backend/routeMaking_agent/pom.xml  backend/routeMaking_agent/
COPY backend/tripPlanner_agent/pom.xml  backend/tripPlanner_agent/
COPY maven-settings.xml .
RUN mvn -B -q -s /src/maven-settings.xml dependency:go-offline || true

# 再拷源码。frontend 必须拷：manager 的 copy-frontend 插件要从 ${project.parent.basedir}/frontend 取静态页
COPY backend ./backend
COPY frontend ./frontend

# 三个服务一起打包（-am 会把 commons 一起编译）
RUN mvn -B -pl backend/manager_agent,backend/routeMaking_agent,backend/tripPlanner_agent -am \
    package -DskipTests -Dstyle.color=never -s /src/maven-settings.xml

# ---------- 阶段2：运行 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 三个 jar 都放进同一个镜像，用环境变量 SVC 决定跑哪个
COPY --from=build /src/backend/manager_agent/target/manager_agent-1.0-SNAPSHOT.jar            /app/manager.jar
COPY --from=build /src/backend/routeMaking_agent/target/routeMaking_agent-1.0-SNAPSHOT.jar    /app/route.jar
COPY --from=build /src/backend/tripPlanner_agent/target/tripPlanner_agent-1.0-SNAPSHOT.jar    /app/trip.jar

# --enable-preview 必须带：manager 源码用了预览特性，运行时缺它会 UnsupportedClassVersionError
ENV JAVA_OPTS="-Xms256m -Xmx512m --enable-preview"

# SVC 取值：manager / route / trip
# APP_ARGS 用来覆盖 application.yml 里的机器相关配置（如子 Agent 绑定的 127.0.0.1）
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/${SVC}.jar $APP_ARGS"]
