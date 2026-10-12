#import <Foundation/Foundation.h>
#import <ArchiveKodecKit/ArchiveKodecKit.h>

NS_ASSUME_NONNULL_BEGIN

// Exercise deprecated caps without weakening warnings for the Swift consumer.
FOUNDATION_EXPORT NSError * _Nullable NegativeLegacyLimitError(BOOL gzip) NS_SWIFT_NAME(negativeLegacyLimitError(gzip:));

// Swift cannot implement the protocol's throwing primitive-returning read method.
// Use an Objective-C source to test cleanup errors through the Swift consumer.
@interface ClosingSource : NSObject <AKKRandomAccessSource>
- (instancetype)initWithFailure:(NSError *)failure;
@property (nonatomic, readonly) int32_t closes;
@end

// Exercise legal short reads and source contract failures through the native bridge.
@interface ShortReadSource : NSObject <AKKRandomAccessSource>
- (instancetype)initWithData:(AKKKotlinByteArray *)data chunk:(int32_t)chunk NS_SWIFT_NAME(init(data:chunk:));
@property (nonatomic, readonly) int32_t closes;
@property (nonatomic, readonly) int32_t calls;
@property (nonatomic) int64_t reportedSize;
@end

NS_ASSUME_NONNULL_END
