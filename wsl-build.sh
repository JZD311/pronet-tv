#!/bin/bash
set -euo pipefail
export LANG=C.UTF-8
JH=/mnt/c/Users/Kushmizokov/TV_BOX/build/jdk-17.0.20.1+1
mkdir -p /tmp/jhome/bin
ln -sfn "$JH/bin/java.exe" /tmp/jhome/bin/java.exe
ln -sfn "$JH/bin/java.exe" /tmp/jhome/bin/java
ln -sfn "$JH/bin/keytool.exe" /tmp/jhome/bin/keytool
ln -sfn "$JH/bin/jar.exe" /tmp/jhome/bin/jar
cat > /tmp/jhome/bin/javac << 'EOF'
#!/bin/bash
out=()
for a in "$@"; do
  if [[ "$a" == /mnt/* ]]; then
    if [[ "$a" == *";"* ]]; then
      IFS=";" read -ra parts <<< "$a"
      joined=""
      for p in "${parts[@]}"; do
        [[ "$p" == /mnt/* ]] && p="$(wslpath -w "$p")"
        joined+="${joined:+;}$p"
      done
      a="$joined"
    else
      a="$(wslpath -w "$a")"
    fi
  fi
  out+=("$a")
done
exec /mnt/c/Users/Kushmizokov/TV_BOX/build/jdk-17.0.20.1+1/bin/javac.exe "${out[@]}"
EOF
chmod +x /tmp/jhome/bin/javac
export JAVA_HOME=/tmp/jhome
export ANDROID_HOME=/mnt/c/Users/Kushmizokov/TV_BOX/build/android-sdk
cd /mnt/c/Users/Kushmizokov/TV_BOX/build/free-my-tv
sed -i 's/\r$//' build.sh
bash ./build.sh
