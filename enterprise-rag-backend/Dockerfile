FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /workspace

# 先复制依赖描述，业务代码变化时可以复用 Maven 依赖层。
COPY enterprise-rag-backend/pom.xml ./pom.xml
RUN mvn -q -DskipTests dependency:go-offline

COPY enterprise-rag-backend/src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy AS runtime

RUN groupadd --system ekb \
    && useradd --system --gid ekb --create-home --home-dir /home/ekb ekb \
    && mkdir -p /opt/ekb \
    && chown -R ekb:ekb /opt/ekb

WORKDIR /opt/ekb
COPY --from=builder --chown=ekb:ekb /workspace/target/enterprise-rag-backend-*.jar ./application.jar

USER ekb
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/ekb/application.jar"]

