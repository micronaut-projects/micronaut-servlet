from dataclasses import dataclass

from micronaut.core.annotation import Introspected


@Introspected
@dataclass
class Person:
    name: str | None = None
    age: int = 18
