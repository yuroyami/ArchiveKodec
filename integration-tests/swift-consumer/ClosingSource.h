#import <Foundation/Foundation.h>
#import <ArchiveKodec/ArchiveKodec.h>

NS_ASSUME_NONNULL_BEGIN

// Swift cannot implement the protocol's throwing primitive-returning read method.
// Use an Objective-C source to test cleanup errors through the Swift consumer.
@interface ClosingSource : NSObject <ArchiveKodecRandomAccessSource>
- (instancetype)initWithFailure:(NSError *)failure;
@property (nonatomic, readonly) int32_t closes;
@end

// Exercise legal short reads and source contract failures through the native bridge.
@interface ShortReadSource : NSObject <ArchiveKodecRandomAccessSource>
- (instancetype)initWithData:(ArchiveKodecKotlinByteArray *)data chunk:(int32_t)chunk NS_SWIFT_NAME(init(data:chunk:));
@property (nonatomic, readonly) int32_t closes;
@property (nonatomic, readonly) int32_t calls;
@property (nonatomic) int64_t reportedSize;
@end

NS_ASSUME_NONNULL_END
