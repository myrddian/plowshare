"""Presence is separate from null. This sentinel is omitted only at the wire boundary."""

from enum import Enum


class Unset(Enum):
    TOKEN = 0


UNSET = Unset.TOKEN
