#!/usr/bin/env bash
# Static bug scan with Error Prone: a standalone javac pass over the 26.3 sources with the Error Prone plugin, writing
# class files to build/errorprone/classes (thrown away) so the normal build is untouched and no slower.
# Usage (from the repo root, after a ./gradlew build of the variant you want, which generates BuildVariant):
#   bash tools/errorprone/run-errorprone.sh [extra gradle -P flags, e.g. -PcheatBuild=true]
# Needs JDK 25 on JAVA_HOME and, in $ERRORPRONE_HOME (default C:/Users/Hunter/tools/errorprone-2.50.0), from Maven Central:
#   com.google.errorprone:error_prone_core:2.50.0:with-dependencies and io.github.eisop:dataflow-errorprone:3.41.0-eisop1
# Writes build/errorprone/report.txt (javac's warnings; "N warnings" at the end).
set -u
EP="${ERRORPRONE_HOME:-C:/Users/Hunter/tools/errorprone-2.50.0}"
OUT=build/errorprone
rm -rf "$OUT" && mkdir -p "$OUT/classes"
./gradlew --init-script tools/spotbugs/classpath.gradle writeSpotbugsClasspath "$@" -q < /dev/null || exit 1
CP=$(paste -sd ';' build/spotbugs/aux-classpath.txt)
find src/main/java src/mc26_3/java build/generated/sources/buildVariant -name '*.java' > "$OUT/sources.txt"
# Checks that are style or API advice, not bugs, are turned off so the report is readable.
OFF="-Xep:UnusedVariable:OFF -Xep:UnusedMethod:OFF -Xep:InlineMeSuggester:OFF -Xep:JavaTimeDefaultTimeZone:OFF \
-Xep:StringCaseLocaleUsage:OFF -Xep:MissingSummary:OFF -Xep:InvalidParam:OFF -Xep:InvalidLink:OFF \
-Xep:EmptyBlockTag:OFF -Xep:InvalidBlockTag:OFF -Xep:InvalidInlineTag:OFF -Xep:UnrecognisedJavadocTag:OFF \
-Xep:NotJavadoc:OFF -Xep:EscapedEntity:OFF -Xep:MissingOverride:OFF -Xep:ReferenceEquality:OFF \
-Xep:NonApiType:OFF -Xep:StatementSwitchToExpressionSwitch:OFF -Xep:PatternMatchingInstanceof:OFF \
-Xep:DefaultCharset:OFF -Xep:JdkObsolete:OFF -Xep:InlineFormatString:OFF -Xep:UnnecessaryParentheses:OFF \
-Xep:CanIgnoreReturnValueSuggester:OFF -Xep:AddressSelection:OFF -Xep:EffectivelyPrivate:OFF \
-Xep:MixedMutabilityReturnType:OFF -Xep:StringSplitter:OFF -Xep:BadImport:OFF -Xep:UnnecessaryLambda:OFF"
X="-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED -J--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
-J--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED -J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
-J--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED -J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED \
-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED -J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
-J--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED -J--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
-J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED -J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED -J-Xmx6g"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
# shellcheck disable=SC2086
"$JAVAC" $X -XDcompilePolicy=simple --should-stop=ifError=FLOW -proc:none -encoding UTF-8 --release 25 \
  -Xmaxwarns 100000 -Xmaxerrs 1000 -d "$OUT/classes" -cp "$CP" \
  --processor-path "$EP/error_prone_core-2.50.0-with-dependencies.jar;$EP/dataflow-errorprone-3.41.0-eisop1.jar" \
  "-Xplugin:ErrorProne -XepDisableWarningsInGeneratedCode $OFF" \
  @"$OUT/sources.txt" < /dev/null > "$OUT/report.txt" 2>&1
echo "errorprone exit $?: $(grep -cE ': (warning|error): \[' "$OUT/report.txt") findings -> $OUT/report.txt"
