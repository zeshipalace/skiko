# D3D12MemoryAllocator declarations

`D3D12MemAlloc.h` is unmodified from
[GPUOpen D3D12MemoryAllocator, commit 169895d529dfce00390a20e69c2f516066fe7a3b](https://github.com/GPUOpen-LibrariesAndSDKs/D3D12MemoryAllocator/blob/169895d529dfce00390a20e69c2f516066fe7a3b/src/D3D12MemAlloc.h).
Its MIT license is included at the top of the header.

This is the exact revision in JetBrains Skia `m152-2ca5fe6a81`'s `DEPS`.
The implementation is already linked into the prebuilt Skia static library; we
do **not** compile a second allocator. The release archive omits this declaration
header, so Skiko carries it to configure the allocator via Ganesh's existing
`GrD3DBackendContext.fMemoryAllocator` extension point.

When upgrading Skia, check its `third_party/externals/d3d12allocator` revision and
update this header to match. Do not substitute another allocator revision: its
C++ ABI must match the implementation linked into Skia.
