#!/bin/sh

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# -----------------------------------------------------------------------------
# Start Script for the CATALINA Server
# -----------------------------------------------------------------------------

# Better OS/400 detection: see Bugzilla 31132
# os400 是ibm的操作系统
os400=false
# 判断操作系统是不是os400
case "`uname`" in
OS400*) os400=true;;
esac

# PRG表示脚本路径，如果当前脚本文件是软链接，则会解析出PRG真正文件所在路径
# resolve links - $0 may be a softlink
PRG="$0"

while [ -h "$PRG" ] ; do # -h 判断是否为软链接
  ls=`ls -ld "$PRG"` # 如果为软链接，输出中含有 link -> source 的字符串
  link=`expr "$ls" : '.*-> \(.*\)$'` # 模式匹配出源文件的路径，可以搜索"expr模式匹配"深入了解
  if expr "$link" : '/.*' > /dev/null; then # 匹配 /.*  这里expr会输出匹配个数，如果不是0，说明￥link 包含目录
    PRG="$link"
  else
    PRG=`dirname "$PRG"`/"$link"
  fi
done

# 获取脚本目录路径
PRGDIR=`dirname "$PRG"`
EXECUTABLE=catalina.sh

# Check that target executable exists
# 判断是否是其他操作系统
if $os400; then
  # -x will Only work on the os400 if the files are:
  # 1. owned by the user
  # 2. owned by the PRIMARY group of the user
  # this will not work if the user belongs in secondary groups
  eval
else
  # 判断脚本catalina.sh是否存在并有可执行权限,没有执行权限就退出
  if [ ! -x "$PRGDIR"/"$EXECUTABLE" ]; then
    echo "Cannot find $PRGDIR/$EXECUTABLE"
    echo "The file is absent or does not have execute permission"
    echo "This file is needed to run this program"
    exit 1
  fi
fi

# 执行catalina.sh start
exec "$PRGDIR"/"$EXECUTABLE" start "$@"
