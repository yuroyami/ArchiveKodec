#import "ClosingSource.h"

NSError * _Nullable NegativeLegacyLimitError(BOOL gzip) {
    NSError *error = nil;
    AKKKotlinByteArray *empty = [AKKKotlinByteArray arrayWithSize:0];
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    if (gzip) {
        [AKKGzip.shared decompressInput:empty maximumSize:-1 error:&error];
    } else {
        [AKKZlib.shared decompressInput:empty maximumSize:-1 error:&error];
    }
#pragma clang diagnostic pop
    return error;
}

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
- (int32_t)readOffset:(int64_t)offset into:(AKKKotlinByteArray *)into intoOffset:(int32_t)intoOffset length:(int32_t)length error:(NSError * _Nullable * _Nullable)error {
    if (error) { *error = nil; }
    return -1;
}
// Objective-C protocols have no default methods, so implementers also forward the whole-array read.
- (int32_t)readOffset:(int64_t)offset into:(AKKKotlinByteArray *)into error:(NSError * _Nullable * _Nullable)error {
    return [self readOffset:offset into:into intoOffset:0 length:into.size error:error];
}
@end


@implementation ShortReadSource {
    AKKKotlinByteArray *_data;
    int32_t _chunk;
    int32_t _closes;
    int32_t _calls;
    int64_t _reportedSize;
}
@synthesize reportedSize = _reportedSize;
- (instancetype)initWithData:(AKKKotlinByteArray *)data chunk:(int32_t)chunk {
    self = [super init];
    if (self) {
        _data = data;
        _chunk = chunk;
        _reportedSize = data.size;
    }
    return self;
}
- (int64_t)size { return _reportedSize; }
- (int32_t)closes { return _closes; }
- (int32_t)calls { return _calls; }
- (BOOL)closeAndReturnError:(NSError * _Nullable * _Nullable)error {
    _closes++;
    if (error) { *error = nil; }
    return YES;
}
- (int32_t)readOffset:(int64_t)offset into:(AKKKotlinByteArray *)into intoOffset:(int32_t)intoOffset length:(int32_t)length error:(NSError * _Nullable * _Nullable)error {
    _calls++;
    if (error) { *error = nil; }
    NSParameterAssert(offset >= 0);
    NSParameterAssert(intoOffset >= 0 && length >= 0 && intoOffset <= into.size && length <= into.size - intoOffset);
    if (length == 0) { return 0; }
    if (offset >= _data.size || offset >= _reportedSize) { return -1; }
    if (_chunk <= 0) { return 0; }
    int64_t available = MIN((int64_t)_data.size - offset, _reportedSize - offset);
    int32_t count = (int32_t)MIN(MIN((int64_t)length, (int64_t)_chunk), available);
    for (int32_t i = 0; i < count; i++) {
        [into setIndex:intoOffset + i value:[_data getIndex:(int32_t)offset + i]];
    }
    return count;
}
// Objective-C protocols have no default methods, so implementers also forward the whole-array read.
- (int32_t)readOffset:(int64_t)offset into:(AKKKotlinByteArray *)into error:(NSError * _Nullable * _Nullable)error {
    return [self readOffset:offset into:into intoOffset:0 length:into.size error:error];
}
@end
