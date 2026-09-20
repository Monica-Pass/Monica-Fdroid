"""Exercise the real compiled GPG generator after R8 with production keep rules.

Build the Android app first. Pass the exact resolved dependency jars from that
build; no replacement crypto implementation or UI mocks are used here.
Output contains pass/fail only, never generated private keys or passphrases.
"""
import argparse
import pathlib
import subprocess
import tempfile
import zipfile


def run(args):
    subprocess.run([str(a) for a in args], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=pathlib.Path, required=True)
    parser.add_argument('--r8', type=pathlib.Path, required=True)
    parser.add_argument('--classes', type=pathlib.Path, required=True)
    parser.add_argument('--dependency', type=pathlib.Path, action='append', required=True)
    parser.add_argument('--rules', type=pathlib.Path,
                        default=pathlib.Path(__file__).resolve().parents[2] / 'app/proguard-rules.pro')
    parser.add_argument('--android-jar', type=pathlib.Path)
    parser.add_argument('--adb', type=pathlib.Path)
    parser.add_argument('--serial')
    args = parser.parse_args()
    if bool(args.adb) != bool(args.android_jar):
        parser.error('--adb and --android-jar must be supplied together')
    java = args.java_home / 'bin/java'
    javac = args.java_home / 'bin/javac'
    import os
    with tempfile.TemporaryDirectory(prefix='monica-gpg-r8-') as temporary:
        work = pathlib.Path(temporary)
        generator = work / 'generator.jar'
        classes = list((args.classes / 'takagi/ru/monica/utils').glob('GpgKeyGenerator*.class'))
        if not classes:
            raise RuntimeError('Build the app first: compiled GpgKeyGenerator classes are missing')
        with zipfile.ZipFile(generator, 'w') as output:
            for file in classes:
                output.write(file, file.relative_to(args.classes))
        source = pathlib.Path(__file__).with_name('GpgRepro.java')
        run([javac, '-encoding', 'UTF-8', '-cp',
             os.pathsep.join(map(str, [generator, *args.dependency])), '-d', work, source])
        entry = work / 'entry.jar'
        with zipfile.ZipFile(entry, 'w') as output:
            output.write(work / 'GpgRepro.class', 'GpgRepro.class')
        probe_rules = work / 'probe.pro'
        # App-only classes/annotations referenced by production rules are absent
        # in this focused harness. Warnings are ignored only in the harness.
        probe_rules.write_text('-keep public class GpgRepro { public static void main(java.lang.String[]); }\n'
                               '-dontwarn **\n', encoding='utf-8')
        shrunk = work / 'shrunk.jar'
        run([java, '-Xmx2048m', '-XX:ActiveProcessorCount=2', '-cp', args.r8,
             'com.android.tools.r8.R8', '--release', '--classfile', '--lib', args.java_home,
             '--pg-conf', probe_rules, '--pg-conf', args.rules, '--output', shrunk,
             entry, generator, *args.dependency])
        executable = work / 'probe.jar'
        # Original dependency JAR signatures do not describe the transformed
        # classes. Android packaging also discards these JAR signature files.
        with zipfile.ZipFile(shrunk) as src, zipfile.ZipFile(executable, 'w') as dst:
            for info in src.infolist():
                name = info.filename.upper()
                if name.startswith('META-INF/') and (
                    name.endswith(('.SF', '.RSA', '.DSA')) or name == 'META-INF/MANIFEST.MF'
                ):
                    continue
                dst.writestr(info, src.read(info.filename))
        run([java, '-Dfile.encoding=UTF-8', '-cp', executable, 'GpgRepro'])
        if args.adb:
            dex = work / 'probe-dex.jar'
            run([java, '-Xmx2048m', '-XX:ActiveProcessorCount=2', '-cp', args.r8,
                 'com.android.tools.r8.D8', '--release', '--min-api', '26',
                 '--lib', args.android_jar, '--output', dex, executable])
            adb = [args.adb] + (['-s', args.serial] if args.serial else [])
            remote = '/data/local/tmp/monica-gpg-r8-regression.jar'
            try:
                run([*adb, 'push', dex, remote])
                run([*adb, 'shell', f'CLASSPATH={remote} app_process /system/bin GpgRepro'])
            finally:
                run([*adb, 'shell', 'rm', '-f', remote])


if __name__ == '__main__':
    main()
