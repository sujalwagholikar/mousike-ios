#import <Foundation/Foundation.h>
NS_ASSUME_NONNULL_BEGIN
@interface MousikePython : NSObject
+ (NSString *)execute:(NSString *)operation payload:(NSString *)payload token:(NSString *)token;
+ (void)cancel:(NSString *)token;
@end
NS_ASSUME_NONNULL_END
