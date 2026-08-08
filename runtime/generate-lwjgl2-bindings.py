#!/usr/bin/env python3
"""Generates the LWJGL 2 OpenGL binding classes of the compatibility layer.

Minecraft before 1.13 calls org.lwjgl.opengl.GL11 and friends, which LWJGL 3 also defines: 243 of
the two APIs' class names under that package collide. The layer therefore declares those classes
itself and forwards each call to LWJGL 3's binding of the same function, reached through the
package the shim jar relocates LWJGL 3's OpenGL module into.

Forwarding is almost entirely mechanical, which is why this is generated rather than written.
Matching Minecraft's entry points against LWJGL 3.3.3 by name and descriptor resolves 163 of 201
outright; a further 31 resolve once LWJGL 2's dropped GL type suffix is put back; 7 are convenience
overloads LWJGL 3 does not carry and are listed by hand below; none are absent. Generating it makes
the transform auditable — the inputs are two published jars and the rules are the three above — and
lets the run fail rather than emit a class that is quietly missing a method the game calls.

The constants are copied wholesale rather than by reference count, because javac inlines
`static final int` at the call site: nothing in a compiled Minecraft or mod jar records that it
read GL11.GL_TRIANGLES, and anything compiled against this layer later will inline it from here.

Usage:
  generate-lwjgl2-bindings.py --lwjgl2 lwjgl-2.9.4.jar --lwjgl3 lwjgl-opengl-3.3.3.jar \
      --require 1.7.10.jar --output app/src/lwjgl2/java
"""

import argparse
import os
import re
import struct
import sys
import zipfile
from collections import OrderedDict

ACC_PUBLIC = 0x0001
ACC_STATIC = 0x0008
ACC_FINAL = 0x0010

# The classes the layer declares. Each forwards to the LWJGL 3 class of the same simple name, whose
# own superclass chain supplies the core-profile half of the API that LWJGL 3 split out (GL11C,
# GL20C and so on) and LWJGL 2 never had.
TARGET_CLASSES = [
    "GL11", "GL12", "GL13", "GL14", "GL15", "GL20", "GL21", "GL30",
    "ARBMultitexture", "ARBOcclusionQuery", "ARBShaderObjects", "ARBVertexShader",
    "ARBVertexBufferObject", "ARBFramebufferObject", "ARBTextureFloat",
    "EXTFramebufferObject", "EXTBlendFuncSeparate", "EXTTextureFilterAnisotropic",
]

# The type-inferring overloads LWJGL 2 offered and LWJGL 3 dropped: the GL type each one implies
# has to be passed explicitly instead. Bodies are written against the generated parameter names
# p0, p1, ... and against GL3, which stands for the relocated class being forwarded to.
ADAPTERS = {
    ("GL11", "glVertexPointer", "(IILjava/nio/FloatBuffer;)V"):
        ["GL3.glVertexPointer(p0, GL_FLOAT, p1, p2);"],
    ("GL11", "glVertexPointer", "(IILjava/nio/IntBuffer;)V"):
        ["GL3.glVertexPointer(p0, GL_INT, p1, p2);"],
    ("GL11", "glVertexPointer", "(IILjava/nio/ShortBuffer;)V"):
        ["GL3.glVertexPointer(p0, GL_SHORT, p1, p2);"],
    ("GL11", "glTexCoordPointer", "(IILjava/nio/FloatBuffer;)V"):
        ["GL3.glTexCoordPointer(p0, GL_FLOAT, p1, p2);"],
    ("GL11", "glTexCoordPointer", "(IILjava/nio/IntBuffer;)V"):
        ["GL3.glTexCoordPointer(p0, GL_INT, p1, p2);"],
    ("GL11", "glTexCoordPointer", "(IILjava/nio/ShortBuffer;)V"):
        ["GL3.glTexCoordPointer(p0, GL_SHORT, p1, p2);"],
    ("GL11", "glNormalPointer", "(ILjava/nio/FloatBuffer;)V"):
        ["GL3.glNormalPointer(GL_FLOAT, p0, p1);"],
    ("GL11", "glNormalPointer", "(ILjava/nio/ByteBuffer;)V"):
        ["GL3.glNormalPointer(GL_BYTE, p0, p1);"],
    ("GL11", "glNormalPointer", "(ILjava/nio/IntBuffer;)V"):
        ["GL3.glNormalPointer(GL_INT, p0, p1);"],
    ("GL11", "glColorPointer", "(IZILjava/nio/ByteBuffer;)V"):
        ["GL3.glColorPointer(p0, p1 ? GL_UNSIGNED_BYTE : GL_BYTE, p2, p3);"],
    ("GL11", "glColorPointer", "(IILjava/nio/FloatBuffer;)V"):
        ["GL3.glColorPointer(p0, GL_FLOAT, p1, p2);"],
    # LWJGL 2 took the shader body as a buffer of bytes and LWJGL 3 takes it as text. Reading
    # through a duplicate keeps the caller's position where LWJGL 2 left it.
    ("GL20", "glShaderSource", "(ILjava/nio/ByteBuffer;)V"): [
        "GL3.glShaderSource(p0, org.lwjgl.system.MemoryUtil.memUTF8(p1.duplicate()));",
    ],
    ("ARBShaderObjects", "glShaderSourceARB", "(ILjava/nio/ByteBuffer;)V"): [
        "GL3.glShaderSourceARB(p0, org.lwjgl.system.MemoryUtil.memUTF8(p1.duplicate()));",
    ],
}

# Which GL type suffix to try putting back, by the buffer type the call takes. LWJGL 2 dropped the
# suffix because the buffer's own type already said which one it was; LWJGL 3 kept GL's name.
SUFFIXES_BY_BUFFER = OrderedDict([
    ("java/nio/FloatBuffer", ["fv", "f", "v"]),
    ("java/nio/IntBuffer", ["iv", "uiv", "i", "v"]),
    ("java/nio/ShortBuffer", ["sv", "usv", "v"]),
    ("java/nio/ByteBuffer", ["bv", "ubv", "v"]),
    ("java/nio/DoubleBuffer", ["dv", "d", "v"]),
    ("java/nio/LongBuffer", ["i64v", "ui64v", "v"]),
])

# Trailing vendor tags sit after the suffix in GL's own naming, so they have to come off before it
# is put back and go on again afterwards.
VENDOR_TAGS = ["ARB", "EXT", "NV", "ATI", "AMD", "APPLE", "SGIS", "SGIX", "SUN", "IBM", "INTEL",
               "OES", "KHR", "MESA", "3DFX", "HP", "PGI", "REND", "WIN", "GREMEDY", "INGR", "S3",
               "SGI"]


class ClassFile(object):
    """Just enough of the class file format to read names, descriptors and constant values."""

    def __init__(self, data):
        if data[:4] != b"\xca\xfe\xba\xbe":
            raise ValueError("not a class file")
        self.pool = {}
        p = 8
        count = struct.unpack_from(">H", data, p)[0]
        p += 2
        index = 1
        while index < count:
            tag = data[p]
            p += 1
            if tag == 1:
                length = struct.unpack_from(">H", data, p)[0]
                p += 2
                self.pool[index] = ("utf8", data[p:p + length].decode("utf-8", "replace"))
                p += length
            elif tag in (7, 8, 16, 19, 20):
                self.pool[index] = ("ref1", struct.unpack_from(">H", data, p)[0])
                p += 2
            elif tag == 15:
                p += 3
            elif tag == 3:
                self.pool[index] = ("int", struct.unpack_from(">i", data, p)[0])
                p += 4
            elif tag == 4:
                self.pool[index] = ("float", struct.unpack_from(">f", data, p)[0])
                p += 4
            elif tag == 5:
                self.pool[index] = ("long", struct.unpack_from(">q", data, p)[0])
                p += 8
                index += 1
            elif tag == 6:
                self.pool[index] = ("double", struct.unpack_from(">d", data, p)[0])
                p += 8
                index += 1
            elif tag in (9, 10, 11, 12, 17, 18):
                self.pool[index] = ("ref2", struct.unpack_from(">HH", data, p))
                p += 4
            else:
                raise ValueError("unknown constant pool tag %d" % tag)
            index += 1

        p += 2  # access flags
        this_index = struct.unpack_from(">H", data, p)[0]
        super_index = struct.unpack_from(">H", data, p + 2)[0]
        p += 4
        self.name = self._utf(self.pool[this_index][1])
        self.supername = self._utf(self.pool[super_index][1]) if super_index else None

        interfaces = struct.unpack_from(">H", data, p)[0]
        p += 2 + 2 * interfaces
        self.fields, p = self._members(data, p)
        self.methods, p = self._members(data, p)

    def _utf(self, index):
        return self.pool[index][1]

    def _members(self, data, p):
        count = struct.unpack_from(">H", data, p)[0]
        p += 2
        out = []
        for _ in range(count):
            access, name_index, desc_index = struct.unpack_from(">HHH", data, p)
            p += 6
            attributes = struct.unpack_from(">H", data, p)[0]
            p += 2
            constant = None
            for _ in range(attributes):
                attr_name, attr_len = struct.unpack_from(">HI", data, p)
                if self._utf(attr_name) == "ConstantValue":
                    constant = self.pool[struct.unpack_from(">H", data, p + 6)[0]]
                p += 6 + attr_len
            out.append((access, self._utf(name_index), self._utf(desc_index), constant))
        return out, p


def load_package(jar_path, package):
    """Every class in one package of a jar, keyed by simple name."""
    prefix = package.replace(".", "/") + "/"
    classes = {}
    with zipfile.ZipFile(jar_path) as jar:
        for entry in jar.namelist():
            if not entry.startswith(prefix) or not entry.endswith(".class"):
                continue
            simple = entry[len(prefix):-len(".class")]
            if "/" in simple:
                continue
            classes[simple] = ClassFile(jar.read(entry))
    return classes


def chain(classes, simple):
    """`simple` and the superclasses of it that live in the same package, nearest first.

    Both libraries put part of each class's API on a supertype: LWJGL 3 split every GL version into
    a core class the compatibility class extends (GL11C under GL11), and LWJGL 2 shared extension
    entry points through common bases (ARBBufferObject under ARBVertexBufferObject). Java resolves
    an inherited static method through the subclass name, so for reading and for forwarding alike
    the chain is one namespace.
    """
    out = []
    current = simple
    while current is not None and current in classes:
        out.append(classes[current])
        supername = classes[current].supername
        current = supername.rsplit("/", 1)[-1] if supername else None
    return out


def inherited_methods(classes, simple):
    """Public static methods visible through `simple`, mapped to the class that declares each."""
    seen = {}
    for cls in chain(classes, simple):
        for access, name, desc, _ in cls.methods:
            if access & ACC_STATIC and access & ACC_PUBLIC:
                seen.setdefault((name, desc), cls.name)
    return seen


def inherited_members(classes, simple):
    """Public fields and public static methods visible through `simple`, nearest declaration wins.

    The generated class has no superclass of its own, so whatever LWJGL 2 exposed through one has
    to be declared on it directly.
    """
    fields = OrderedDict()
    methods = OrderedDict()
    for cls in chain(classes, simple):
        for access, name, desc, constant in cls.fields:
            if access & ACC_PUBLIC:
                fields.setdefault(name, (access, name, desc, constant))
        for access, name, desc, _ in cls.methods:
            if access & ACC_STATIC and access & ACC_PUBLIC:
                methods.setdefault((name, desc), (access, name, desc))
    return list(fields.values()), list(methods.values())


def split_vendor(name):
    for tag in VENDOR_TAGS:
        if name.endswith(tag) and len(name) > len(tag):
            return name[:-len(tag)], tag
    return name, ""


def parse_descriptor(desc):
    params = []
    index = 1
    while desc[index] != ")":
        start = index
        while desc[index] == "[":
            index += 1
        if desc[index] == "L":
            index = desc.index(";", index) + 1
        else:
            index += 1
        params.append(desc[start:index])
    return params, desc[index + 1:]


def buffer_types(desc):
    params, _ = parse_descriptor(desc)
    return [p[1:-1] for p in params if p.startswith("L") and p[1:-1] in SUFFIXES_BY_BUFFER]


def resolve(name, desc, available):
    """Find LWJGL 3's counterpart. Returns (target name, rule) or (None, None).

    A candidate is only accepted when its descriptor is identical, so a suffix guess cannot land on
    a different function: two GL entry points that differ only by suffix differ in their buffer
    type, and therefore in their descriptor, as well.
    """
    if (name, desc) in available:
        return name, "exact"
    base, vendor = split_vendor(name)
    for buffer in buffer_types(desc):
        for suffix in SUFFIXES_BY_BUFFER[buffer]:
            candidate = base + suffix + vendor
            if (candidate, desc) in available:
                return candidate, "suffix"
    return None, None


JAVA_TYPES = {
    "V": "void", "Z": "boolean", "B": "byte", "C": "char", "S": "short",
    "I": "int", "J": "long", "F": "float", "D": "double",
}


def java_type(desc):
    if desc.startswith("["):
        return java_type(desc[1:]) + "[]"
    if desc.startswith("L"):
        return desc[1:-1].replace("/", ".")
    return JAVA_TYPES[desc]


def constant_literal(desc, value):
    kind, raw = value
    if desc == "J":
        return "%dL" % raw
    if desc == "F":
        if raw != raw or raw in (float("inf"), float("-inf")):
            return "Float.NaN" if raw != raw else ("Float.POSITIVE_INFINITY" if raw > 0
                                                   else "Float.NEGATIVE_INFINITY")
        return "%rf" % raw
    if desc == "D":
        return "%r" % raw
    if desc == "Z":
        return "true" if raw else "false"
    if desc == "C":
        return "(char) %d" % raw
    if desc == "B":
        return "(byte) %d" % raw
    if desc == "S":
        return "(short) %d" % raw
    if desc == "I":
        return "0x%X" % (raw & 0xFFFFFFFF) if raw >= 0 else "%d" % raw
    if desc == "Ljava/lang/String;":
        return '"%s"' % raw.replace("\\", "\\\\").replace('"', '\\"')
    raise ValueError("no literal for %s" % desc)


def scan_references(jar_path, package):
    """The (class, name, descriptor) members of `package` a jar's constant pool names.

    Only methods and non-constant fields appear: javac inlines `static final int`, so the game's
    use of GL11.GL_TRIANGLES leaves no trace to find.
    """
    prefix = package.replace(".", "/") + "/"
    found = set()
    with zipfile.ZipFile(jar_path) as jar:
        for entry in jar.namelist():
            if not entry.endswith(".class"):
                continue
            try:
                cls = ClassFile(jar.read(entry))
            except ValueError:
                continue
            for index, value in cls.pool.items():
                if value[0] != "ref2":
                    continue
                class_index, nat_index = value[1]
                owner_entry = cls.pool.get(class_index)
                nat_entry = cls.pool.get(nat_index)
                if not owner_entry or owner_entry[0] != "ref1":
                    continue
                if not nat_entry or nat_entry[0] != "ref2":
                    continue
                owner = cls.pool[owner_entry[1]][1]
                if not owner.startswith(prefix) or "/" in owner[len(prefix):]:
                    continue
                name = cls.pool[nat_entry[1][0]][1]
                desc = cls.pool[nat_entry[1][1]][1]
                found.add((owner[len(prefix):], name, desc))
    return found


def emit_class(simple, lwjgl2, lwjgl3_available, relocated, required, report):
    fields, methods = inherited_members(lwjgl2, simple)
    # Written out in full at every call site: the class being forwarded to has the same simple name
    # as the one being declared, so it cannot be imported, and Java has no import alias.
    gl3 = "%s.%s" % (relocated, simple)
    lines = []
    lines.append("// Generated by runtime/generate-lwjgl2-bindings.py. Do not edit.")
    lines.append("//")
    lines.append("// LWJGL 2's %s. Every method forwards to LWJGL 3's binding of the same GL"
                 % simple)
    lines.append("// function, reached through the package the compatibility jar relocates")
    lines.append("// LWJGL 3's OpenGL module into. The constants are LWJGL 2's own values.")
    lines.append("package org.lwjgl.opengl;")
    lines.append("")
    lines.append("public final class %s {" % simple)
    lines.append("")

    constants = 0
    for access, name, desc, constant in sorted(fields, key=lambda f: f[1]):
        if not (access & ACC_STATIC and access & ACC_FINAL) or constant is None:
            continue
        lines.append("    public static final %s %s = %s;"
                     % (java_type(desc), name, constant_literal(desc, constant)))
        constants += 1
    if constants:
        lines.append("")

    emitted = 0
    for access, name, desc in sorted(methods, key=lambda m: (m[1], m[2])):
        key = (simple, name, desc)
        params, ret = parse_descriptor(desc)
        signature = ", ".join("%s p%d" % (java_type(p), i) for i, p in enumerate(params))
        arguments = ", ".join("p%d" % i for i in range(len(params)))

        if key in ADAPTERS:
            body = [line.replace("GL3.", gl3 + ".") for line in ADAPTERS[key]]
            report["adapter"].append(key)
        else:
            target, rule = resolve(name, desc, lwjgl3_available)
            if target is None:
                report["absent"].append(key)
                continue
            report[rule].append(key)
            call = "%s.%s(%s)" % (gl3, target, arguments)
            body = [("return %s;" % call) if ret != "V" else ("%s;" % call)]

        lines.append("    public static %s %s(%s) {" % (java_type(ret), name, signature))
        for line in body:
            lines.append("        %s" % line)
        lines.append("    }")
        lines.append("")
        emitted += 1

    lines.append("    private %s() {" % simple)
    lines.append("    }")
    lines.append("}")
    return "\n".join(lines) + "\n", constants, emitted


CAPABILITIES_BODY = '''
    /**
     * Reads the extension set and version straight out of the GL implementation and sets every
     * field named after what it found.
     *
     * Deliberately not a field-by-field copy of LWJGL 3's GLCapabilities: that class models 440 of
     * the 2369 flags LWJGL 2 declared and drops seven Minecraft reads, and going to the driver
     * instead makes this a policy layer rather than a translation. {@link #GL_ARB_occlusion_query}
     * below is what that buys.
     */
    static ContextCapabilities fromCurrentContext() {
        ContextCapabilities capabilities = new ContextCapabilities();
        int[] version = readVersion();
        Set<String> extensions = readExtensions(version);
        for (Field field : ContextCapabilities.class.getFields()) {
            if (field.getType() == boolean.class && field.getName().startsWith("GL_")) {
                try {
                    field.setBoolean(capabilities, extensions.contains(field.getName()));
                } catch (IllegalAccessException ignored) {
                    // Every field here is public and non-final; nothing can reach this.
                }
            }
        }
        capabilities.applyVersion(version);

        // gl4es exports glBeginQuery and friends but never increments the sample counter -- its
        // queries.c sets the count to zero and reports zero counter bits for GL_SAMPLES_PASSED --
        // so every occlusion query answers "nothing was drawn". Minecraft's Advanced OpenGL culling
        // would take that literally and render an empty world. Every version that issues an
        // occlusion query reads this flag first, so denying it here is enough to keep them all on
        // the path that does not.
        capabilities.GL_ARB_occlusion_query = false;
        return capabilities;
    }

    private static Set<String> readExtensions(int[] version) {
        Set<String> extensions = new HashSet<String>();
        // The one space-separated GL_EXTENSIONS string is asked for first, and the indexed form
        // only where the string is gone: it was removed in the 3.0 core profile, but reading it is
        // what every pre-1.13 Minecraft itself does, and the translation layers this runs on
        // report 2.1. Calling glGetStringi against a 2.x context would be a null function pointer.
        String joined = GL3.glGetString(0x1F03);
        if (joined != null) {
            for (String name : joined.split(" ")) {
                if (name.length() > 0) {
                    extensions.add(name);
                }
            }
            return extensions;
        }
        if (version[0] >= 3) {
            int count = GL3_30.glGetInteger(0x821D);
            for (int i = 0; i < count; i++) {
                String name = GL3_30.glGetStringi(0x1F03, i);
                if (name != null) {
                    extensions.add(name);
                }
            }
        }
        return extensions;
    }

    private static int[] readVersion() {
        String version = GL3.glGetString(0x1F02);
        if (version == null) {
            return new int[] { 1, 1 };
        }
        Matcher matcher = Pattern.compile("(\\\\d+)\\\\.(\\\\d+)").matcher(version);
        if (!matcher.find()) {
            return new int[] { 1, 1 };
        }
        return new int[] {
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
        };
    }

    private void applyVersion(int[] version) {
        for (Field field : ContextCapabilities.class.getFields()) {
            if (field.getType() != boolean.class || !field.getName().startsWith("OpenGL")) {
                continue;
            }
            String digits = field.getName().substring("OpenGL".length());
            if (digits.length() != 2) {
                continue;
            }
            int major = digits.charAt(0) - '0';
            int minor = digits.charAt(1) - '0';
            boolean supported = version[0] > major || (version[0] == major && version[1] >= minor);
            try {
                field.setBoolean(this, supported);
            } catch (IllegalAccessException ignored) {
                // As above.
            }
        }
    }
'''


def emit_capabilities(lwjgl2, relocated):
    fields, _ = inherited_members(lwjgl2, "ContextCapabilities")
    lines = []
    lines.append("// Generated by runtime/generate-lwjgl2-bindings.py. Do not edit.")
    lines.append("//")
    lines.append("// LWJGL 2's ContextCapabilities. The fields are LWJGL 2's own list, declared so")
    lines.append("// that a game compiled against it finds every flag it reads; they are filled in")
    lines.append("// from the GL implementation rather than copied from LWJGL 3's GLCapabilities.")
    lines.append("//")
    lines.append("// LWJGL 2 declared these final. They are not, here, because they are assigned")
    lines.append("// reflectively by name -- which is what keeps 2369 flags correct without 2369")
    lines.append("// lines of generated assignment, and what a final instance field forbids.")
    lines.append("package org.lwjgl.opengl;")
    lines.append("")
    lines.append("import java.lang.reflect.Field;")
    lines.append("import java.util.HashSet;")
    lines.append("import java.util.Set;")
    lines.append("import java.util.regex.Matcher;")
    lines.append("import java.util.regex.Pattern;")
    lines.append("")
    lines.append("public final class ContextCapabilities {")
    lines.append("")

    count = 0
    for access, name, desc, _ in fields:
        if desc != "Z":
            continue
        lines.append("    public boolean %s;" % name)
        count += 1
    lines.append("")
    body = CAPABILITIES_BODY.strip("\n")
    body = body.replace("GL3_30.", "%s.GL30." % relocated).replace("GL3.", "%s.GL11." % relocated)
    lines.append(body)
    lines.append("}")
    return "\n".join(lines) + "\n", count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lwjgl2", required=True, help="the org.lwjgl.lwjgl:lwjgl jar")
    parser.add_argument("--lwjgl3", required=True, help="the org.lwjgl:lwjgl-opengl jar")
    parser.add_argument("--require", action="append", default=[],
                        help="a client jar whose entry points must all resolve")
    parser.add_argument("--relocated", default="com.github.lodestone.lwjgl3.opengl",
                        help="the package the shim jar relocates LWJGL 3's OpenGL module into")
    parser.add_argument("--output", required=True, help="the source root to write into")
    args = parser.parse_args()

    lwjgl2 = load_package(args.lwjgl2, "org.lwjgl.opengl")
    lwjgl3 = load_package(args.lwjgl3, "org.lwjgl.opengl")

    required = set()
    for jar in args.require:
        for owner, name, desc in scan_references(jar, "org.lwjgl.opengl"):
            required.add((owner, name, desc))

    report = {"exact": [], "suffix": [], "adapter": [], "absent": []}
    directory = os.path.join(args.output, "org", "lwjgl", "opengl")
    if not os.path.isdir(directory):
        os.makedirs(directory)

    for simple in TARGET_CLASSES:
        if simple not in lwjgl2:
            sys.exit("%s is not declared by %s" % (simple, args.lwjgl2))
        if simple not in lwjgl3:
            sys.exit("%s is not declared by %s" % (simple, args.lwjgl3))
        available = inherited_methods(lwjgl3, simple)
        source, constants, methods = emit_class(
            simple, lwjgl2, available, args.relocated, required, report)
        with open(os.path.join(directory, simple + ".java"), "w") as handle:
            handle.write(source)
        print("    %-28s %4d constants %4d methods" % (simple, constants, methods))

    source, flags = emit_capabilities(lwjgl2, args.relocated)
    with open(os.path.join(directory, "ContextCapabilities.java"), "w") as handle:
        handle.write(source)
    print("    %-28s %4d flags" % ("ContextCapabilities", flags))

    print()
    print("    exact   %5d" % len(report["exact"]))
    print("    suffix  %5d" % len(report["suffix"]))
    print("    adapter %5d" % len(report["adapter"]))
    print("    absent  %5d" % len(report["absent"]))

    # The entry points the game calls are the ones that must not be missing. The rest is headroom
    # for mods, and a dead vendor extension no backend implements can be left out without loss.
    covered = set()
    for rule in ("exact", "suffix", "adapter"):
        covered.update(report[rule])
    wanted = [k for k in required if k[0] in TARGET_CLASSES and k[2].startswith("(")]
    unreachable = sorted(k for k in wanted if k not in covered)
    print("    called by the client jars: %d, of which unresolved: %d"
          % (len(wanted), len(unreachable)))
    if unreachable:
        for key in unreachable:
            print("      MISSING %s.%s%s" % key, file=sys.stderr)
        sys.exit("%d entry point(s) the game calls have no counterpart" % len(unreachable))


if __name__ == "__main__":
    main()
