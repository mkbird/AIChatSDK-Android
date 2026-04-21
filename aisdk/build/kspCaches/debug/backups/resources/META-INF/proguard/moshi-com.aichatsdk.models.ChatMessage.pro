-if class com.aichatsdk.models.ChatMessage
-keepnames class com.aichatsdk.models.ChatMessage
-if class com.aichatsdk.models.ChatMessage
-keep class com.aichatsdk.models.ChatMessageJsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
}
-if class com.aichatsdk.models.ChatMessage
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-if class com.aichatsdk.models.ChatMessage
-keepclassmembers class com.aichatsdk.models.ChatMessage {
    public synthetic <init>(java.lang.String,java.lang.String,com.aichatsdk.models.Role,long,java.util.List,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
