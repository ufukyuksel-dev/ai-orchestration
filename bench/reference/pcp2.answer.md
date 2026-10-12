The owner search pages its results with `PageRequest.of(page - 1, pageSize)` where `pageSize = 5`
(`OwnerController#findPaginatedForOwnersLastName`). Both `VetRepository.findAll` methods are annotated
`@Cacheable("vets")`, and `CacheConfiguration` creates that cache.

ANSWER:
pageSize: 5
cache: vets
