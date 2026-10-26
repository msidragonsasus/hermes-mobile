"""Descriptor-bound directory ownership and publication for administrator tools."""
from contextlib import contextmanager
import os
from pathlib import Path
import stat
import uuid


def identity(value):
    return value.st_dev, value.st_ino


@contextmanager
def directory_fd(path, *, trusted=False):
    path = Path(path).absolute()
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    descriptor = os.open(path.anchor, flags)
    try:
        parts = path.parts[1:]
        for part in parts:
            current = os.fstat(descriptor)
            if current.st_uid not in {0, os.geteuid()} or current.st_mode & 0o022:
                raise RuntimeError("Untrusted directory ancestor")
            child = os.open(part, flags, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        if trusted:
            current = os.fstat(descriptor)
            if current.st_uid not in {0, os.geteuid()} or current.st_mode & 0o022:
                raise RuntimeError("Untrusted staging parent")
        yield descriptor
    finally:
        os.close(descriptor)


def prepare_private(base, name, user):
    with directory_fd(base) as parent:
        try:
            os.mkdir(name, 0o700, dir_fd=parent)
        except FileExistsError:
            pass
        descriptor = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
        try:
            if os.fstat(descriptor).st_uid != user.pw_uid and os.listdir(descriptor):
                raise RuntimeError("Existing private storage owner differs; contents retained")
            os.fchmod(descriptor, 0o700)
            os.fchown(descriptor, user.pw_uid, user.pw_gid)
            if identity(os.fstat(descriptor)) != identity(os.stat(name, dir_fd=parent, follow_symlinks=False)):
                raise RuntimeError("Private storage changed; configuration retained")
        finally:
            os.close(descriptor)


def prepare_stage(base, prefix, user):
    base = Path(base)
    name = prefix + uuid.uuid4().hex
    with directory_fd(base.parent, trusted=True) as parent:
        os.mkdir(name, 0o700, dir_fd=parent)
        descriptor = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
        try:
            expected = identity(os.fstat(descriptor))
            os.fchown(descriptor, user.pw_uid, user.pw_gid)
            if identity(os.stat(name, dir_fd=parent, follow_symlinks=False)) != expected:
                raise RuntimeError("Staging directory changed")
        finally:
            os.close(descriptor)
    return base.parent / name, expected


def publish_stage(stage, destination, expected):
    stage, destination = Path(stage), Path(destination)
    with directory_fd(stage.parent, trusted=True) as source:
        with directory_fd(destination.parent) as target:
            current = os.stat(stage.name, dir_fd=source, follow_symlinks=False)
            if not stat.S_ISDIR(current.st_mode) or identity(current) != expected:
                raise RuntimeError("Staging directory changed; runtime retained")
            try:
                os.stat(destination.name, dir_fd=target, follow_symlinks=False)
            except FileNotFoundError:
                pass
            else:
                raise RuntimeError("Runtime appeared during preparation; retained")
            os.rename(stage.name, destination.name, src_dir_fd=source, dst_dir_fd=target)
