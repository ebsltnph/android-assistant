# 混淆规则（release 构建使用；当前 release 未开混淆，此文件暂为空）
# jlatexmath 通过反射/资源加载字体与 TeX 元素，保留其类防止日后开混淆时崩
-keep class org.scilab.forge.jlatexmath.** { *; }
