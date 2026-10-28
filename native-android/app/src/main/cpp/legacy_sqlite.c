#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sodium.h>

/* Use the public SQLite C ABI exported by the already packaged SQLCipher library.
 * The in-memory reader lives in an isolated process and has no new-vault keys. */
typedef struct {
    void *library, *db, *statement;
    unsigned char *snapshot;
    uint64_t size;
    int (*open)(const char *,void **);
    int (*close)(void *);
    void *(*allocate)(uint64_t);
    void (*free_memory)(void *);
    int (*deserialize)(void *,const char *,unsigned char *,int64_t,int64_t,unsigned);
    int (*prepare)(void *,const char *,int,void **,const char **);
    int (*step)(void *);
    int (*finalize)(void *);
    const unsigned char *(*text)(void *,int);
    int (*bytes)(void *,int);
    int (*exec)(void *,const char *,void *,void *,char **);
    int (*limit)(void *,int,int);
} MemoryReader;
static void cleanup(MemoryReader *r) {
    if(!r)return;
    if(r->statement)r->finalize(r->statement);
    if(r->db)r->close(r->db);
    if(r->snapshot){sodium_memzero(r->snapshot,(size_t)r->size);r->free_memory(r->snapshot);}
    if(r->library)dlclose(r->library);
    free(r);
}
static void fail(JNIEnv *env,const char *message) {(*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),message);}
JNIEXPORT jlong JNICALL Java_app_securenote_vault_LegacyMemory_openNative(JNIEnv *env,jobject self,jbyteArray snapshot) {
    (void)self;
    if(!snapshot){fail(env,"Missing legacy snapshot");return 0;}
    jsize size=(*env)->GetArrayLength(env,snapshot);
    if(size<100||size>64*1024*1024){fail(env,"Invalid legacy snapshot size");return 0;}
    MemoryReader *r=calloc(1,sizeof *r);
    if(!r){fail(env,"Insufficient memory");return 0;}
    r->library=dlopen("libsqlcipher.so",RTLD_NOW|RTLD_LOCAL);
    if(!r->library){cleanup(r);fail(env,"Legacy database engine unavailable");return 0;}
#define LOAD(field,symbol) do { *(void **)(&r->field)=dlsym(r->library,symbol); if(!r->field){cleanup(r);fail(env,"Unsupported database ABI");return 0;} } while(0)
    LOAD(open,"sqlite3_open");LOAD(close,"sqlite3_close_v2");LOAD(allocate,"sqlite3_malloc64");LOAD(free_memory,"sqlite3_free");LOAD(deserialize,"sqlite3_deserialize");LOAD(prepare,"sqlite3_prepare_v2");LOAD(step,"sqlite3_step");LOAD(finalize,"sqlite3_finalize");LOAD(text,"sqlite3_column_text");LOAD(bytes,"sqlite3_column_bytes");LOAD(exec,"sqlite3_exec");LOAD(limit,"sqlite3_limit");
    r->snapshot=r->allocate((uint64_t)size+20);r->size=(uint64_t)size+20;
    if(!r->snapshot){cleanup(r);fail(env,"Insufficient legacy memory");return 0;}
    memset(r->snapshot,0,(size_t)r->size);
    (*env)->GetByteArrayRegion(env,snapshot,0,size,(jbyte *)r->snapshot);
    if(memcmp(r->snapshot,"SQLite format 3\0",16)){cleanup(r);fail(env,"Invalid database header");return 0;}
    /* A checkpointed WAL snapshot needs rollback header flags for deserialize. */
    r->snapshot[18]=1;r->snapshot[19]=1;
    if(r->open(":memory:",&r->db)||r->deserialize(r->db,"main",r->snapshot,size,(int64_t)r->size,4)) {
        cleanup(r);fail(env,"Invalid legacy database");return 0;
    }
    r->limit(r->db,0,16*1024*1024); /* SQLITE_LIMIT_LENGTH */
    r->exec(r->db,"PRAGMA trusted_schema=OFF;PRAGMA temp_store=MEMORY;PRAGMA query_only=ON",NULL,NULL,NULL);
    void *check=NULL;
    if(r->prepare(r->db,"SELECT type FROM sqlite_master WHERE name='notes'",-1,&check,NULL)||r->step(check)!=100||!r->text(check,0)||strcmp((const char *)r->text(check,0),"table")) {
        if(check)r->finalize(check);cleanup(r);fail(env,"Missing legacy notes table");return 0;
    }
    r->finalize(check);
    int result=r->prepare(r->db,"SELECT id,title,content,updatedAt,isPinned,type FROM notes",-1,&r->statement,NULL);
    if(result){if(r->statement)r->finalize(r->statement);r->statement=NULL;result=r->prepare(r->db,"SELECT id,title,content,updatedAt,0,'text' FROM notes",-1,&r->statement,NULL);}
    if(result){cleanup(r);fail(env,"Unsupported legacy schema");return 0;}
    return (jlong)(intptr_t)r;
}
JNIEXPORT jobjectArray JNICALL Java_app_securenote_vault_LegacyMemory_nextNative(JNIEnv *env,jobject self,jlong handle) {
    (void)self;MemoryReader *r=(MemoryReader *)(intptr_t)handle;
    if(!r){fail(env,"Closed legacy reader");return NULL;}
    int result=r->step(r->statement);
    if(result==101)return NULL;
    if(result!=100){fail(env,"Corrupt legacy notes");return NULL;}
    jobjectArray row=(*env)->NewObjectArray(env,6,(*env)->FindClass(env,"[B"),NULL);
    if(!row)return NULL;
    for(int i=0;i<6;i++){
        int n=r->bytes(r->statement,i);
        if(n<0||n>16*1024*1024){fail(env,"Legacy note too large");return NULL;}
        jbyteArray value=(*env)->NewByteArray(env,n);
        if(!value)return NULL;
        if(n)(*env)->SetByteArrayRegion(env,value,0,n,(const jbyte *)r->text(r->statement,i));
        (*env)->SetObjectArrayElement(env,row,i,value);(*env)->DeleteLocalRef(env,value);
    }
    return row;
}
JNIEXPORT void JNICALL Java_app_securenote_vault_LegacyMemory_closeNative(JNIEnv *env,jobject self,jlong handle) {(void)env;(void)self;cleanup((MemoryReader *)(intptr_t)handle);}
