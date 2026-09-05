#pragma once

#include "ganesh/d3d/GrD3DTypes.h"
#include "vendor/D3D12MemAlloc.h"

// Same allocator and allocation lifetime as Skia's GrD3DAMDMemoryAllocator, with
// a configurable preferred block size. A Ganesh cache budget limits live resources,
// not the slack in the D3D12 heaps backing those resources. Small UI workloads should
// not have to retain a large heap because one tiny, frequently used texture is alive.
// Larger resources still use D3D12MA's committed-resource path. No eviction or scan is
// performed here: suballocations are returned when Skia releases the last GPU reference.
namespace {
class SkikoD3DMemoryAllocator final : public GrD3DMemoryAllocator {
    class Allocation final : public GrD3DAlloc {
    public:
        explicit Allocation(D3D12MA::Allocation* allocation) : value(allocation) {}
        ~Allocation() override { value->Release(); }
        D3D12MA::Allocation* value;
    };

public:
    static sk_sp<GrD3DMemoryAllocator> Make(IDXGIAdapter* adapter, ID3D12Device* device,
                                           UINT64 preferredBlockSize) {
        D3D12MA::ALLOCATOR_DESC desc = {};
        desc.pAdapter = adapter;
        desc.pDevice = device;
        // Ganesh's Direct3D context is externally serialized, as with its default allocator.
        desc.Flags = D3D12MA::ALLOCATOR_FLAG_SINGLETHREADED;
        desc.PreferredBlockSize = preferredBlockSize;
        D3D12MA::Allocator* allocator = nullptr;
        if (FAILED(D3D12MA::CreateAllocator(&desc, &allocator))) {
            return nullptr;
        }
        return sk_sp<GrD3DMemoryAllocator>(new SkikoD3DMemoryAllocator(allocator));
    }

    ~SkikoD3DMemoryAllocator() override { allocator->Release(); }

    gr_cp<ID3D12Resource> createResource(
        D3D12_HEAP_TYPE heapType, const D3D12_RESOURCE_DESC* resourceDesc,
        D3D12_RESOURCE_STATES initialState, sk_sp<GrD3DAlloc>* allocation,
        const D3D12_CLEAR_VALUE* clearValue) override {
        D3D12MA::ALLOCATION_DESC desc = {};
        desc.HeapType = heapType;
        gr_cp<ID3D12Resource> resource;
        D3D12MA::Allocation* nativeAllocation = nullptr;
        if (FAILED(allocator->CreateResource(&desc, resourceDesc, initialState, clearValue,
                                            &nativeAllocation, IID_PPV_ARGS(&resource)))) {
            return nullptr;
        }
        allocation->reset(new Allocation(nativeAllocation));
        return resource;
    }

    gr_cp<ID3D12Resource> createAliasingResource(
        sk_sp<GrD3DAlloc>& allocation, uint64_t localOffset,
        const D3D12_RESOURCE_DESC* resourceDesc, D3D12_RESOURCE_STATES initialState,
        const D3D12_CLEAR_VALUE* clearValue) override {
        auto* nativeAllocation = static_cast<Allocation*>(allocation.get());
        gr_cp<ID3D12Resource> resource;
        if (FAILED(allocator->CreateAliasingResource(nativeAllocation->value, localOffset,
                                                    resourceDesc, initialState, clearValue,
                                                    IID_PPV_ARGS(&resource)))) {
            return nullptr;
        }
        return resource;
    }

private:
    explicit SkikoD3DMemoryAllocator(D3D12MA::Allocator* allocator) : allocator(allocator) {}
    D3D12MA::Allocator* allocator;
};
} // namespace
