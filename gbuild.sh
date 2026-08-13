#!/usr/bin/env bash
# pika 构建脚本：绕过 wrapper 直接调用已解压的 Gradle 8.14.2。
# 构建前清理 ~/.gradle 下残留锁（本沙箱内 lock 文件无法重新打开）。
# 注意：必须在**关闭沙箱隔离**的情况下运行，否则 rm 会被静默拒绝。
set -u

GRADLE_HOME="C:/Users/wacil/.gradle/wrapper/dists/gradle-8.14.2-bin/9mqvzzl3fbcgp4a9l34v26dsb/gradle-8.14.2"
JAVA_BIN="/c/Program Files/Android/Android Studio/jbr/bin/java.exe"
LOG="/c/Users/wacil/AppData/Local/Temp/pika_build.log"

# 找到真实解压目录（hash 目录名不确定）
if [ ! -f "$GRADLE_HOME/lib/gradle-launcher-8.14.2.jar" ]; then
  REAL=$(ls -d /c/Users/wacil/.gradle/wrapper/dists/gradle-8.14.2-bin/*/gradle-8.14.2 2>/dev/null | head -1)
  if [ -n "$REAL" ]; then GRADLE_HOME=$(cygpath -m "$REAL"); fi
fi

rm -rf /c/Users/wacil/.gradle/native /c/Users/wacil/.gradle/daemon 2>/dev/null
if [ -e /c/Users/wacil/.gradle/native ]; then
  echo "!! ~/.gradle/native 未能删除（沙箱？），构建大概率会失败" >&2
fi

# 与 gradle.properties 的 org.gradle.jvmargs 保持一致，避免 fork 单次 daemon
JVM_ARGS=(
  -Dfile.encoding=UTF-8
  -XX:+UseG1GC
  -XX:SoftRefLRUPolicyMSPerMB=1
  -XX:ReservedCodeCacheSize=512m
  -XX:MaxMetaspaceSize=1024m
  -XX:+HeapDumpOnOutOfMemoryError
  -Xms2g
  -Xmx4g
)
OPENS=(
  --add-opens=java.base/java.lang=ALL-UNNAMED
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
  --add-opens=java.base/java.util=ALL-UNNAMED
  --add-opens=java.prefs/java.util.prefs=ALL-UNNAMED
  --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
  --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
  --add-opens=java.base/java.nio.charset=ALL-UNNAMED
  --add-opens=java.base/java.net=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
  --add-opens=java.xml/javax.xml.namespace=ALL-UNNAMED
  --add-opens=java.base/java.time=ALL-UNNAMED
)

"$JAVA_BIN" "${JVM_ARGS[@]}" "${OPENS[@]}" \
  -Dorg.gradle.appname=gradle \
  -classpath "$GRADLE_HOME/lib/gradle-launcher-8.14.2.jar" \
  org.gradle.launcher.GradleMain \
  --no-daemon --console=plain "$@" > "$LOG" 2>&1
EXIT=$?

echo "EXIT=$EXIT"
grep -E "^e: |FAILURE|BUILD SUCCESSFUL|BUILD FAILED|error:|single-use Daemon" "$LOG" | head -40
echo "(完整日志: $LOG)"
exit $EXIT
