#!/system/bin/sh
# T2 ANR 压力：全部间隔 <1s，总时长 ~2 分钟
# Phase1: 内置包开关快速切换 12 次（起始 ON，偶数次后仍 ON）
i=0
while [ $i -lt 12 ]; do input tap 843 1019; sleep 0.7; i=$((i+1)); done
# Phase2: 管理页 <-> 设置根 快速往返 6 轮
i=0
while [ $i -lt 6 ]; do input keyevent 4; sleep 0.6; input tap 321 1780; sleep 0.8; i=$((i+1)); done
# Phase3: 导入页(SAF) <-> 管理页 快速往返 5 轮
i=0
while [ $i -lt 5 ]; do input tap 851 2245; sleep 0.8; input keyevent 4; sleep 0.6; i=$((i+1)); done
# Phase4: 设置 <-> 正则 tab 快速切换 10 次
i=0
while [ $i -lt 10 ]; do input tap 761 2256; sleep 0.4; input tap 981 2256; sleep 0.4; i=$((i+1)); done
# Phase5: 回到管理页，内置包开关再快速切换 6 次（偶数后仍 ON）
input tap 321 1780; sleep 0.8
i=0
while [ $i -lt 6 ]; do input tap 843 1019; sleep 0.5; i=$((i+1)); done
echo STRESS-DONE
