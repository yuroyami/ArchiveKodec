#import "ClosingSource.h"

@implementation ClosingSource {
    NSError *_failure;
    int32_t _closes;
}
- (instancetype)initWithFailure:(NSError *)failure {
    self = [super init];
    if (self) { _failure = failure; }
    return self;
}
- (int64_t)size { return 0; }
- (int32_t)closes { return _closes; }
- (BOOL)closeAndReturnError:(NSError * _Nullable * _Nullable)error {
    _closes++;
    if (error) { *error = _failure; }
    return NO;
}
- (int32_t)readOffset:(int64_t)offset into:(ArchiveKodecKotlinByteArray *)into intoOffset:(int32_t)intoOffset length:(int32_t)length error:(NSError * _Nullable * _Nullable)error {
    if (error) { *error = nil; }
    return -1;
}
@end
