package com.example.notionreader.ui

/**
 * Purpose-built 100-Note benchmark corpus: ten distinct technical subjects with ten Notes each.
 * The text is synthetic and contains no user data. Each subject uses stable anchors so a
 * proposal can be specific, while the ten subjects give a countable usefulness denominator.
 *
 * The corpus is frozen: changing it invalidates comparison with earlier benchmark runs.
 */
internal object DiscoveryBenchmarkCorpus {
    const val version = 1

    data class Subject(val id: String, val title: String, val facts: List<String>)

    val subjects: List<Subject> = listOf(
        Subject("postgres", "PostgreSQL transaction isolation", listOf(
            "PostgreSQL repeatable read fixes a transaction snapshot at the first query, so later statements observe the same committed rows until the transaction ends.",
            "PostgreSQL serializable isolation adds predicate locking so concurrent transactions cannot produce a write skew that repeatable read would allow.",
            "PostgreSQL read committed takes a new snapshot for each statement, so two reads in one transaction can observe different committed updates.",
            "A PostgreSQL serialization failure under serializable isolation requires the application to retry the whole transaction rather than retry a single statement.",
            "PostgreSQL row locks block conflicting writers but do not block readers, because multiversion concurrency control keeps older row versions visible to snapshots.",
            "Long-running PostgreSQL transactions delay vacuum because the oldest snapshot still needs its row versions, which increases table bloat.",
            "PostgreSQL advisory locks are application-defined and do not participate in snapshot isolation, so they serialize coordination outside the visibility rules.",
            "Choosing PostgreSQL isolation level trades consistency against contention: stronger isolation rejects more concurrent writes with serialization errors.",
            "PostgreSQL exposes transaction isolation per session and per transaction, so a connection pool must not assume every checkout has the same level.",
            "A PostgreSQL transaction that only reads can still be aborted under serializable isolation when a conflicting write commits first."
        )),
        Subject("rabbitmq", "RabbitMQ acknowledgement and redelivery", listOf(
            "RabbitMQ manual acknowledgement keeps a delivery unacknowledged until the consumer confirms processing, so a crash returns the message to the queue.",
            "RabbitMQ automatic acknowledgement removes the message before the consumer finishes, so a failed handler cannot trigger redelivery.",
            "RabbitMQ negative acknowledgement can requeue a delivery or discard it, and requeueing may deliver the same message to a different consumer.",
            "RabbitMQ consumer prefetch limits unacknowledged deliveries per channel, which bounds memory and spreads work across competing consumers.",
            "A RabbitMQ delivery that is requeued repeatedly can loop forever unless the consumer tracks attempts or routes failures to a dead-letter exchange.",
            "RabbitMQ publisher confirms prove the broker accepted a message, which is separate from any consumer acknowledgement of processing.",
            "RabbitMQ durable queues and persistent messages survive a broker restart only when both are declared durable and the message is published as persistent.",
            "RabbitMQ redelivery sets a redelivered flag so consumers can distinguish a first attempt from a repeated delivery after a disconnect.",
            "RabbitMQ acknowledgement is scoped to a channel, so closing the channel returns all unacknowledged deliveries on it to the queue.",
            "RabbitMQ at-least-once delivery means handlers must tolerate duplicate processing because acknowledgement can be lost after the work succeeds."
        )),
        Subject("tls", "TLS handshake and certificate validation", listOf(
            "A TLS handshake authenticates the server certificate before any application data is sent, so a hostname mismatch aborts the connection.",
            "TLS certificate validation checks the chain to a trusted root, the validity dates and the requested hostname; skipping any check defeats authentication.",
            "TLS forward secrecy comes from an ephemeral key exchange, so compromising the server long-term key does not decrypt recorded sessions.",
            "TLS session resumption shortens a repeat handshake but must still bind the resumed session to the authenticated server identity.",
            "TLS certificate revocation is often checked through OCSP or a CRL, and soft-fail behaviour can leave a revoked certificate accepted.",
            "TLS version negotiation rejects downgrade to obsolete protocol versions when the client enforces a modern minimum.",
            "TLS client certificates authenticate the client to the server and require the server to validate the presented chain as well.",
            "TLS handshake latency grows with round trips, so a full handshake on a high-latency link is slower than resuming an existing session.",
            "TLS certificate pinning narrows trust to a known key or certificate and must define a rotation path or the app stops connecting.",
            "TLS encrypts the transport but not data at rest, so a compromised endpoint still exposes plaintext after decryption."
        )),
        Subject("btree", "B-tree index behaviour", listOf(
            "A B-tree index answers equality and range predicates by descending the tree, so a range scan reads leaves in key order.",
            "A B-tree index stores entries in sorted key order, which makes an index scan able to return ordered rows without a separate sort.",
            "A composite B-tree index supports a predicate on its leading column efficiently, but a predicate on a trailing column alone cannot seek.",
            "B-tree page splits occur when a leaf fills, and random insert keys cause more splits and fragmentation than append-only keys.",
            "A B-tree index covering query includes every referenced column in the index so the heap is not visited for the projection.",
            "B-tree index bloat from deleted entries is reclaimed by vacuum or rebuild, and a bloated index costs more pages per lookup.",
            "A B-tree index does not help a predicate that applies a function to the indexed column unless a matching expression index exists.",
            "B-tree index uniqueness is enforced by the index itself, so a duplicate insert fails before the row is committed.",
            "A B-tree index on a low-selectivity column may be ignored by the planner because a sequential scan reads fewer pages.",
            "B-tree index traversal cost grows logarithmically with row count, while the leaf range cost grows with the number of matching rows."
        )),
        Subject("crdt", "CRDT conflict resolution", listOf(
            "A CRDT converges without coordination because every replica applies commutative operations in any order and reaches the same state.",
            "A state-based CRDT merges whole replica states with a join that is commutative, associative and idempotent.",
            "An operation-based CRDT requires reliable causal delivery of operations, so a dropped operation prevents convergence.",
            "A last-writer-wins register resolves concurrent writes by timestamp, which silently discards the losing value.",
            "A multi-value register preserves concurrent writes so the application can resolve the conflict explicitly instead of losing data.",
            "A grow-only counter CRDT sums per-replica increments and converges because increment is commutative and never removed.",
            "An observed-remove set CRDT tags elements with unique identifiers so a concurrent add survives a remove that did not observe it.",
            "A sequence CRDT such as RGA orders concurrent insertions by identifier, which keeps replicas convergent without a central order.",
            "CRDT metadata grows with the number of operations or replicas, so compaction or garbage collection is required for long-lived documents.",
            "A CRDT gives eventual consistency, not immediate consistency, so a read can observe a stale replica until merges propagate."
        )),
        Subject("gc", "Garbage collection pauses", listOf(
            "A stop-the-world garbage collection pause halts application threads while the collector traces and moves live objects.",
            "A concurrent garbage collector does most marking alongside the application but still needs short pauses for root scanning and relocation.",
            "Garbage collection pause time grows with the live set because tracing must visit every reachable object.",
            "A generational garbage collector collects young objects frequently and promotes survivors, which keeps young collections short.",
            "Garbage collection throughput trades against latency: a collector that minimizes pauses usually spends more total CPU.",
            "Allocation rate drives garbage collection frequency, so reducing temporary allocations lowers pause count more than lowering heap size.",
            "A garbage collection heap that is too small causes frequent collections, while a heap that is too large delays reclamation and raises footprint.",
            "Reference objects and finalizers extend object lifetime and can delay garbage collection of the referent by a full cycle.",
            "Compacting garbage collection moves objects to reduce fragmentation, which requires updating every reference to a moved object.",
            "Garbage collection logs expose pause duration and reclaimed bytes, which is how a latency regression is attributed to the collector."
        )),
        Subject("vector", "Approximate nearest neighbour search", listOf(
            "Approximate nearest neighbour search trades recall for speed by pruning candidates instead of comparing every vector.",
            "An HNSW index builds a layered proximity graph and searches from sparse upper layers down to dense lower layers.",
            "An inverted file index partitions vectors into clusters and probes only the nearest clusters, so recall depends on the probe count.",
            "Product quantization compresses vectors into codes, which lowers memory but introduces approximation error in distance estimates.",
            "A vector index recall is measured against exact search on the same queries, and a high recall at low latency is the useful operating point.",
            "Embedding dimensionality affects both index size and distance cost, so truncation must be evaluated rather than assumed safe.",
            "Filtering during vector search is harder than post-filtering because a restrictive filter can leave too few candidates in probed clusters.",
            "A vector index requires rebuilding after a model change because distances from different embedding spaces are not comparable.",
            "Cosine similarity and inner product rank differently unless vectors are normalized, so the index metric must match the model contract.",
            "Approximate nearest neighbour search returns candidates for reranking; it is not evidence that a candidate satisfies a semantic condition."
        )),
        Subject("tcp", "TCP congestion control", listOf(
            "TCP slow start grows the congestion window exponentially until it reaches the slow-start threshold or observes loss.",
            "TCP congestion avoidance grows the window linearly, which probes for spare capacity without collapsing the connection.",
            "TCP treats packet loss as a congestion signal and reduces the congestion window, which lowers send rate for that connection.",
            "TCP receive window limits in-flight data by receiver buffer space, which is separate from the congestion window.",
            "TCP fast retransmit repairs a lost segment after duplicate acknowledgements instead of waiting for a retransmission timeout.",
            "TCP retransmission timeout backs off exponentially, so repeated loss can leave a connection idle for seconds.",
            "TCP cubic and BBR react differently to loss and delay, so throughput on a lossy path depends on the congestion control algorithm.",
            "TCP head-of-line blocking delays later bytes when an earlier segment is lost, even if later segments arrived.",
            "TCP connection establishment costs a round trip before data, and TLS adds further round trips on top.",
            "TCP flow control prevents a fast sender from overrunning a slow receiver, while congestion control protects the network."
        )),
        Subject("k8s", "Kubernetes pod scheduling", listOf(
            "The Kubernetes scheduler filters nodes by resource requests and taints, then scores the remaining nodes to pick a placement.",
            "A Kubernetes pod that requests more CPU than any node advertises stays pending until the request is reduced or capacity is added.",
            "Kubernetes node affinity constrains scheduling by labels, while pod affinity co-locates pods that must run near each other.",
            "Kubernetes taints and tolerations keep workloads off dedicated nodes unless the pod explicitly tolerates the taint.",
            "A Kubernetes pod disruption budget limits voluntary evictions so a rolling update cannot drain every replica at once.",
            "Kubernetes resource requests drive scheduling, while limits cap runtime usage; a missing request makes placement unreliable.",
            "Kubernetes topology spread constraints distribute replicas across zones so one zone failure does not remove every copy.",
            "A Kubernetes evicted pod is recreated by its controller, which can move it to a different node with different locality.",
            "Kubernetes horizontal pod autoscaling adds replicas from observed load, but new pods still wait for scheduling and image pulls.",
            "Kubernetes scheduler plugins can reorder or reject placements, so a custom plugin changes which node a pod receives."
        )),
        Subject("tracing", "Distributed tracing propagation", listOf(
            "Distributed tracing propagates a trace identifier and parent span identifier across service calls so one request forms a single tree.",
            "A tracing span records start time, duration and status, and a child span must reference its parent to preserve causality.",
            "Context propagation through HTTP headers is required for a trace to continue across a process boundary; an in-process context is not enough.",
            "Sampling decides which traces are recorded, and a head-based decision must propagate so every service samples the same trace.",
            "Tail-based tracing sampling decides after the trace completes, which allows keeping slow or failed traces but requires buffering.",
            "A tracing clock skew between hosts distorts span durations, so duration should be computed from one clock where possible.",
            "Baggage propagates arbitrary key-value pairs across services, which is convenient but can leak sensitive data if unbounded.",
            "A missing trace context starts a new trace, which breaks the causal tree and hides cross-service latency.",
            "Trace spans for asynchronous work must be linked explicitly, because a background task does not inherit the caller's stack.",
            "Distributed tracing shows where time is spent but does not by itself prove correctness of the traced operation."
        ))
    )

    /** Exactly 100 Notes: ten subjects in ten Notes each. */
    fun notes(): List<Pair<String, Pair<String, String>>> = buildList {
        subjects.forEachIndexed { subjectIndex, subject ->
            subject.facts.forEachIndexed { factIndex, fact ->
                val id = "bench${subjectIndex + 1}-${factIndex + 1}"
                val title = "${subject.title} ${factIndex + 1}"
                add(id to (title to fact))
            }
        }
    }

    /** Ten independent useful subjects: the usefulness denominator for a published-topic review. */
    val expectedUsefulSubjects: Int get() = subjects.size
}
