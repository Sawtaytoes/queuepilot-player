FROM eclipse-temurin:21-jdk-jammy@sha256:e0c60c487345d1dc9d0fc7b6f0496f3cc941e5132e09296cc17a6decc71b902b
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip git python3 libpulse0 libnss3 libx11-6 libxcb1 libxcomposite1 libxcursor1 libxdamage1 libxi6 libxtst6 libxrandr2 libgl1 libasound2 libxkbcommon0 && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk GRADLE_USER_HOME=/cache/gradle
RUN mkdir -p /opt/android-sdk/cmdline-tools && curl -fsSLo /tmp/sdk.zip https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip && echo "4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  /tmp/sdk.zip" | sha256sum -c - && unzip -q /tmp/sdk.zip -d /opt/android-sdk/cmdline-tools && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest && rm /tmp/sdk.zip
ENV PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/emulator:/opt/gradle/bin:$PATH
RUN yes | sdkmanager --licenses >/dev/null && sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'emulator' 'system-images;android-30;android-tv;x86'
RUN curl -fsSLo /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.13-bin.zip && curl -fsSLo /tmp/gradle.sha256 https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256 && echo "$(cat /tmp/gradle.sha256)  /tmp/gradle.zip" | sha256sum -c - && unzip -q /tmp/gradle.zip -d /opt && mv /opt/gradle-8.13 /opt/gradle && rm /tmp/gradle.zip /tmp/gradle.sha256
WORKDIR /source/android
CMD ["gradle", "--no-daemon", "testDebugUnitTest", "lintDebug", "ktlintCheck", "assembleDebug", "assembleRelease"]
