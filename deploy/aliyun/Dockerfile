FROM public.ecr.aws/docker/library/eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      libreoffice-impress \
      libreoffice-writer \
      fonts-noto-cjk \
      fontconfig \
    && fc-cache -f \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=65.0", "-jar", "/app/talent-platform.jar"]
