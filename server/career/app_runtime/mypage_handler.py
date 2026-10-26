"""App-specific Bot entry, with only a fixed enqueue adapter supplied by host.

This is trusted, reviewed Python, NOT a Python security sandbox. Production
editing/activation is not exposed by the mobile API or enabled by this slice.
"""


def enqueue(adapter, company_id, profile):
    return adapter.enqueue_existing(company_id, profile)
