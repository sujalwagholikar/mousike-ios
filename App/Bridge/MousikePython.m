#import "MousikePython.h"
#import <Python/Python.h>

// A shared native session provides pooling, system TLS and bounded responses.
// Python always calls this on a worker. No UI-thread networking or loopback server.
@interface MKRequest : NSObject
@property(nonatomic,strong) NSMutableData *data;
@property(nonatomic,strong) dispatch_semaphore_t done;
@property(nonatomic,strong) NSURLSessionDataTask *task;
@property(nonatomic,strong) NSError *error;
@property(nonatomic,copy) NSString *token;
@property(nonatomic) NSInteger status;
@end
@implementation MKRequest
- (instancetype)init { if ((self=[super init])) { _data=[NSMutableData data]; _done=dispatch_semaphore_create(0); } return self; }
@end

@interface MKTransport : NSObject <NSURLSessionDataDelegate,NSURLSessionTaskDelegate>
@property(nonatomic,strong) NSURLSession *session;
@property(nonatomic,strong) NSMutableDictionary<NSNumber *,MKRequest *> *requests;
@property(nonatomic,strong) NSMutableSet<NSString *> *cancelled;
+ (instancetype)shared;
@end
@implementation MKTransport
+ (instancetype)shared {
    static MKTransport *value; static dispatch_once_t once;
    dispatch_once(&once,^{ value=[MKTransport new]; value.requests=[NSMutableDictionary dictionary]; value.cancelled=[NSMutableSet set];
        NSURLSessionConfiguration *config=[NSURLSessionConfiguration ephemeralSessionConfiguration];
        config.timeoutIntervalForRequest=7; config.timeoutIntervalForResource=8;
        config.HTTPMaximumConnectionsPerHost=4; config.URLCache=nil;
        NSOperationQueue *queue=[NSOperationQueue new]; queue.maxConcurrentOperationCount=2;
        value.session=[NSURLSession sessionWithConfiguration:config delegate:value delegateQueue:queue];
    }); return value;
}
- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)task didReceiveResponse:(NSURLResponse *)response completionHandler:(void (^)(NSURLSessionResponseDisposition))completionHandler {
    @synchronized(self) { MKRequest *r=self.requests[@(task.taskIdentifier)]; r.status=[(NSHTTPURLResponse *)response statusCode];
        completionHandler(response.expectedContentLength > 4*1024*1024 ? NSURLSessionResponseCancel : NSURLSessionResponseAllow); }
}
- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)task didReceiveData:(NSData *)data {
    @synchronized(self) { MKRequest *r=self.requests[@(task.taskIdentifier)]; if (!r) return;
        if (r.data.length+data.length>4*1024*1024) { r.error=[NSError errorWithDomain:@"Mousike" code:413 userInfo:@{NSLocalizedDescriptionKey:@"Response exceeds 4 MiB"}]; [task cancel]; }
        else [r.data appendData:data]; }
}
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    @synchronized(self) { MKRequest *r=self.requests[@(task.taskIdentifier)]; if (!r) return; if (!r.error) r.error=error; dispatch_semaphore_signal(r.done); }
}
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task willPerformHTTPRedirection:(NSHTTPURLResponse *)response newRequest:(NSURLRequest *)request completionHandler:(void (^)(NSURLRequest *))completionHandler { completionHandler(nil); }
@end

static PyObject *native_request(PyObject *self,PyObject *args) {
    const char *token,*address,*headerJSON,*body;
    if (!PyArg_ParseTuple(args,"ssss",&token,&address,&headerJSON,&body)) return NULL;
    @autoreleasepool {
        NSString *t=[NSString stringWithUTF8String:token]; NSURL *url=[NSURL URLWithString:[NSString stringWithUTF8String:address]];
        if (![url.scheme isEqualToString:@"https"] || ![@[@"www.youtube.com",@"music.youtube.com"] containsObject:url.host] || ![url.path hasPrefix:@"/youtubei/v1/"] || url.user || url.password || (url.port && url.port.intValue!=443)) {
            PyErr_SetString(PyExc_ValueError,"Unsupported API URL"); return NULL;
        }
        MKTransport *transport=[MKTransport shared]; MKRequest *r=[MKRequest new]; r.token=t;
        NSMutableURLRequest *request=[NSMutableURLRequest requestWithURL:url]; request.HTTPMethod=@"POST";
        request.HTTPBody=[[NSString stringWithUTF8String:body] dataUsingEncoding:NSUTF8StringEncoding];
        id headers=[NSJSONSerialization JSONObjectWithData:[[NSString stringWithUTF8String:headerJSON] dataUsingEncoding:NSUTF8StringEncoding] options:0 error:nil];
        if ([headers isKindOfClass:NSDictionary.class]) for (NSString *key in headers) {
            if (![@[@"host",@"content-length",@"connection",@"accept-encoding"] containsObject:key.lowercaseString] && [headers[key] isKindOfClass:NSString.class]) [request setValue:headers[key] forHTTPHeaderField:key];
        }
        @synchronized(transport) {
            if ([transport.cancelled containsObject:t]) { PyErr_SetString(PyExc_InterruptedError,"Request cancelled"); return NULL; }
            r.task=[transport.session dataTaskWithRequest:request]; transport.requests[@(r.task.taskIdentifier)]=r;
        }
        [r.task resume]; long timedOut;
        Py_BEGIN_ALLOW_THREADS
        timedOut=dispatch_semaphore_wait(r.done,dispatch_time(DISPATCH_TIME_NOW,9*NSEC_PER_SEC));
        Py_END_ALLOW_THREADS
        if (timedOut) [r.task cancel];
        @synchronized(transport) { [transport.requests removeObjectForKey:@(r.task.taskIdentifier)]; }
        if (timedOut || r.error) { PyErr_SetString(PyExc_ConnectionError,timedOut ? "Connection timed out" : r.error.localizedDescription.UTF8String); return NULL; }
        NSString *text=[[NSString alloc] initWithData:r.data encoding:NSUTF8StringEncoding];
        if (!text) { PyErr_SetString(PyExc_ValueError,"Invalid API response encoding"); return NULL; }
        NSString *result=[NSString stringWithFormat:@"%ld\n%@",(long)r.status,text]; return PyUnicode_FromString(result.UTF8String);
    }
}
static PyMethodDef methods[]={{"request",native_request,METH_VARARGS,"Bounded native HTTPS"},{NULL,NULL,0,NULL}};
static struct PyModuleDef nativeModule={PyModuleDef_HEAD_INIT,"_mousike_ios",NULL,-1,methods};
PyMODINIT_FUNC PyInit__mousike_ios(void) { return PyModule_Create(&nativeModule); }

static NSString *python_error(void) {
    PyObject *type=NULL,*value=NULL,*tb=NULL; PyErr_Fetch(&type,&value,&tb); PyErr_NormalizeException(&type,&value,&tb);
    PyObject *s=value ? PyObject_Str(value) : NULL;
    const char *utf=s ? PyUnicode_AsUTF8(s) : NULL; NSString *message=utf ? [NSString stringWithUTF8String:utf] : @"Python engine failed";
    Py_XDECREF(s); Py_XDECREF(type); Py_XDECREF(value); Py_XDECREF(tb); PyErr_Clear(); return message;
}

@interface MousikePython ()
+ (nullable NSString *)call:(NSString *)operation payload:(NSString *)payload token:(NSString *)token error:(NSError **)error;
@end

@implementation MousikePython
+ (NSString *)execute:(NSString *)operation payload:(NSString *)payload token:(NSString *)token {
    NSError *error=nil; NSString *result=[self call:operation payload:payload token:token error:&error];
    NSDictionary *envelope=result ? @{ @"result":result } : @{ @"error":error.localizedDescription ?: @"Engine unavailable" };
    return [[NSString alloc] initWithData:[NSJSONSerialization dataWithJSONObject:envelope options:0 error:nil] encoding:NSUTF8StringEncoding];
}
+ (void)cancel:(NSString *)token {
    MKTransport *transport=[MKTransport shared]; @synchronized(transport) {
        if (transport.cancelled.count>128) [transport.cancelled removeAllObjects]; [transport.cancelled addObject:token];
        for (MKRequest *r in transport.requests.allValues) if ([r.token isEqual:token]) [r.task cancel];
    }
}
+ (NSString *)call:(NSString *)operation payload:(NSString *)payload token:(NSString *)token error:(NSError **)error {
    NSAssert(!NSThread.isMainThread,@"Python must run off main thread");
    static dispatch_once_t once; static NSString *initializationError;
    dispatch_once(&once,^{
        NSString *bundle=NSBundle.mainBundle.resourcePath;
        PyImport_AppendInittab("_mousike_ios",PyInit__mousike_ios);
        PyPreConfig pre; PyPreConfig_InitIsolatedConfig(&pre); pre.utf8_mode=1;
        PyStatus status=Py_PreInitialize(&pre);
        if (PyStatus_Exception(status)) { initializationError=@"Python preinitialization failed"; return; }
        PyConfig config; PyConfig_InitIsolatedConfig(&config); config.buffered_stdio=0; config.write_bytecode=0; config.install_signal_handlers=1;
        status=PyConfig_SetBytesString(&config,&config.home,[[bundle stringByAppendingPathComponent:@"python"] UTF8String]);
        if (!PyStatus_Exception(status)) {
            config.module_search_paths_set=1;
            for (NSString *path in @[@"python/lib/python3.13",@"python/lib/python3.13/lib-dynload",@"SharedPython",@"SharedPython/packages"]) {
                wchar_t *wide=Py_DecodeLocale([[bundle stringByAppendingPathComponent:path] UTF8String],NULL);
                status=PyWideStringList_Append(&config.module_search_paths,wide); PyMem_RawFree(wide);
                if (PyStatus_Exception(status)) break;
            }
        }
        if (!PyStatus_Exception(status)) status=Py_InitializeFromConfig(&config);
        if (PyStatus_Exception(status)) initializationError=status.err_msg ? [NSString stringWithUTF8String:status.err_msg] : @"Python initialization failed";
        PyConfig_Clear(&config); if (!initializationError) PyEval_SaveThread();
    });
    if (initializationError) { if(error)*error=[NSError errorWithDomain:@"Mousike" code:1 userInfo:@{NSLocalizedDescriptionKey:initializationError}]; return nil; }
    PyGILState_STATE gil=PyGILState_Ensure(); PyObject *module=PyImport_ImportModule("ios_api"); PyObject *result=NULL;
    if (module) { PyObject *fn=PyObject_GetAttrString(module,"dispatch"); if(fn) { result=PyObject_CallFunction(fn,"sss",operation.UTF8String,payload.UTF8String,token.UTF8String); Py_DECREF(fn); } Py_DECREF(module); }
    NSString *answer=NULL;
    if (result) { const char *utf=PyUnicode_AsUTF8(result); if(utf) answer=[NSString stringWithUTF8String:utf]; Py_DECREF(result); }
    if (!answer && error) *error=[NSError errorWithDomain:@"Mousike" code:2 userInfo:@{NSLocalizedDescriptionKey:python_error()}];
    PyGILState_Release(gil);
    MKTransport *transport=[MKTransport shared]; @synchronized(transport) { [transport.cancelled removeObject:token]; }
    return answer;
}
@end
