# Reference parameters

PX `&` parameters round-trip through a `Ref<T>` box. The VM seeds the
box's `value` from the caller, runs the script, then writes the
(coerced) post-call value back into the same box.

```kotlin
class Ref<T>(@JvmField var value: T)
fun <T> ref(initial: T): Ref<T>
```

There are three flavours of `&` parameter:

| PX shape          | Native sees           | Available on            |
|-------------------|-----------------------|-------------------------|
| `& : cell/float/string/bool` | `Ref<Any?>` (scalar) | `callPublic` & `NCALL`  |
| `& : Struct`      | `Ref<Map<String,Any?>>` | `NCALL` only          |
| `& : Struct[]`    | `Ref<List<Map<String,Any?>>>` | `NCALL` only    |

Public-table struct refs are rejected by the compiler, so on
`callPublic` you only ever deal with **scalar** refs. Native-table refs
support all three shapes.

## Scalar references

### As an output parameter on a public

```pawn
public OnReceive(payload: string, &output: string) {
    output = strcat(payload, "!");
    return 1;
}
```

```kotlin
val out = Ref("")
val ok = script.callPublic("OnReceive", listOf("hello", out)) as Long
println(out.value)    // "hello!"
```

### As an in/out parameter on a public

```pawn
public Bump(&counter: cell) {
    counter = counter + 1;
    return counter;
}
```

```kotlin
val counter = Ref(0L)
script.callPublic("Bump", listOf(counter))   // counter.value -> 1
script.callPublic("Bump", listOf(counter))   // counter.value -> 2
```

### As a native ref

```pawn
native MakeNonce(&out: string);

public main() {
    new nonce = "";
    MakeNonce(nonce);
    return nonce == "" ? 0 : 1;
}
```

```kotlin
vm.registerNative("MakeNonce") { args ->
    @Suppress("UNCHECKED_CAST")
    val out = args[0] as Ref<Any?>
    out.value = java.util.UUID.randomUUID().toString()
    1L
}
```

### Type coercion on writeback

The runtime coerces between cell types according to the **declared**
parameter type, not whatever was last written. So a script that does
`out = 7;` on a `&out: string` parameter still surfaces `out.value =
"7"` to the host.

| Declared | Runtime cell | Box value coerced to |
|----------|--------------|----------------------|
| `cell`   | `Long`       | `Long`               |
| `bool`   | `Long`       | `Long` (0/1)         |
| `float`  | `Double`     | `Double`             |
| `string` | `String`     | `String`             |

## Struct references

Native-only. The host receives a `Ref<StructValue>` (a
`Ref<Map<String, Any?>>`); the keys are the struct entry's `"jsonTag"`
strings, falling back to the entry's declared name if no tag was
provided.

```pawn
struct ContactInfo {
    cFirstName : string  "firstname",
    cLastName  : string  "lastname",
    cSomeInt              "someInt"
};

native GetHubSpotContact(contactId: string, &contact: ContactInfo);
```

```kotlin
vm.registerNative("GetHubSpotContact") { args ->
    val id = args[0] as String
    @Suppress("UNCHECKED_CAST")
    val contact = args[1] as Ref<Any?>
    val row = hubspot.fetchContact(id)
    contact.value = mapOf(
        "firstname" to row.firstName,
        "lastname"  to row.lastName,
        "someInt"   to row.someInt,        // Long is fine here
    )
    1L
}
```

The runtime walks the struct entries on writeback. Missing keys leave
the corresponding cell **untouched** (lenient writeback) — perfect for
"partial update" natives:

```kotlin
contact.value = mapOf("firstname" to "Ada")    // lastname & someInt unchanged
```

Reading the box on entry is symmetric: the runtime serializes the
existing struct row at `&contact`'s heap address and stuffs it into
`contact.value`, so the native can compare incoming-vs-outgoing.

## Struct-array references

Native-only. Declared with `&name[]: Struct`. The host gets a
`Ref<List<StructValue>>` seeded with the current rows. On writeback the
runtime writes back at most `min(box.value.size, declaredLength)` rows;
extra incoming rows are ignored, trailing rows in PX are left alone.

```pawn
struct ContactInfo {
    cFirstName : string  "firstname",
    cLastName  : string  "lastname",
    cSomeInt              "someInt"
};

native ListContacts(&list[]: ContactInfo);

new Contacts[3][ContactInfo];

public DemoList() {
    ListContacts(&Contacts);
    return Contacts[0][cSomeInt];
}
```

```kotlin
vm.registerNative("ListContacts") { args ->
    @Suppress("UNCHECKED_CAST")
    val list = args[0] as Ref<Any?>
    list.value = listOf(
        mapOf("firstname" to "Ada",   "lastname" to "Lovelace", "someInt" to 42L),
        mapOf("firstname" to "Grace", "lastname" to "Hopper",   "someInt" to 7L),
    )
    1L
}
```

## Common pitfalls

- **Forgot to box?** `callPublic("Bump", listOf(0L))` against a
  `& counter: cell` parameter will throw — pass `Ref(0L)` instead.
- **Boxed a value parameter?** Same in reverse — pass the value
  directly.
- **`Long` vs `Int`** — the cell representation is `Long`. Boxing an
  `Int` works (the runtime coerces), but you'll get a `Long` back, not
  the original `Int`.
- **Reusing a `Ref` across calls** — fine, encouraged. The runtime
  always reads `box.value` afresh on entry.
- **Concurrent calls** — a `Script` is not thread-safe; serialize
  external access (e.g. via a lock) or load one `Script` per request.
