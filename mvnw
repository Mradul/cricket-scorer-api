#!/bin/sh
# Quarkus 3.15.x requires Java 17+ and Maven 3.9.x (3.6.3 fails with Mirror.isBlocked)
# Auto-select correct runtime if available, otherwise fallback.
if [ -x "/usr/local/java-runtime/impl/21/bin/java" ]; then
  JAVA_HOME="/usr/local/java-runtime/impl/21"
  export JAVA_HOME
  PATH="$JAVA_HOME/bin:$PATH"
  export PATH
elif [ -x "/usr/local/fbprojects/packages/java-runtime/525/impl/21/bin/java" ]; then
  JAVA_HOME="/usr/local/fbprojects/packages/java-runtime/525/impl/21"
  export JAVA_HOME
  PATH="$JAVA_HOME/bin:$PATH"
  export PATH
fi
if [ -x "/tmp/apache-maven-3.9.9/bin/mvn" ]; then
  exec /tmp/apache-maven-3.9.9/bin/mvn "$@"
else
  exec mvn "$@"
fi
