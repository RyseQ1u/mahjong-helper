# R8 规则（release 已开启 minify + shrinkResources）。
#
# Compose、Room、coroutines、AndroidX 均自带 consumer rules，正常无需补充；
# 这里只做与反射/可回溯性相关的保守声明。

# ---- 可回溯性：保留行号，便于崩溃栈定位 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Room ----
# AppDatabase_Impl 由 Room.databaseBuilder 按类名反射实例化。
-keep class * extends androidx.room.RoomDatabase { <init>(); }
# 实体类字段参与列名映射，保留字段名可避免后续新增 @ColumnInfo 时的隐式依赖。
-keep @androidx.room.Entity class * { *; }

# ---- 说明 ----
# 本应用无 native 库、无反射式序列化、无 JS bridge，故不引入额外 keep 规则。
-dontwarn android.**
