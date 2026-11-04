# Android build image for the Diptych companion app (adapted from ttw-dualscreen).
# Usage (from repo root):
#   docker build -t diptych-android -f tools/docker/android.Dockerfile .
#   docker run --rm --user $(id -u):$(id -g) -v diptych-gradle:/gradle \
#     -v $PWD:/repo -w /repo/app diptych-android ./gradlew testDebugUnitTest assembleDebug
# The whole repo is mounted because the build copies ../protocol/kh1/fixtures into test resources.
# Mock smoke test: add --network host and run `./gradlew testDebugUnitTest --rerun -Pmock=1
#   --tests '*SpikeClientSmokeTest'`, then (once it prints "SMOKE start") run on the host
#   tools/mockserver/.venv/bin/python tools/mockserver/kh1_mock.py --stall-after 10
FROM eclipse-temurin:17-jdk

ARG CMDLINE_TOOLS_BUILD=11076708
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle \
    HOME=/gradle/home
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

RUN apt-get update && apt-get install -y --no-install-recommends unzip curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
    && curl -fsSL -o /tmp/clt.zip \
       https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip \
    && unzip -q /tmp/clt.zip -d ${ANDROID_HOME}/cmdline-tools \
    && mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
    && rm /tmp/clt.zip

RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools" > /dev/null \
    && chmod -R a+rX ${ANDROID_HOME}

# World-writable Gradle home so the container can run as the host user (--user uid:gid)
# with a named volume mounted at /gradle (the volume inherits these permissions).
RUN mkdir -p /gradle/home && chmod -R 1777 /gradle
