#import <Foundation/Foundation.h>
#import <ArchiveKodec/ArchiveKodec.h>

NS_ASSUME_NONNULL_BEGIN

// Swift cannot implement the protocol's throwing primitive-returning read method.
// Use an Objective-C source to test cleanup errors through the Swift consumer.
@interface ClosingSource : NSObject <ArchiveKodecRandomAccessSource>
- (instancetype)initWithFailure:(NSError *)failure;
@property (nonatomic, readonly) int32_t closes;
@end

NS_ASSUME_NONNULL_END
