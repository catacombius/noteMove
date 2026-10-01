#!/usr/bin/env python3
"""Split a reference Live 11 set (uncompressed XML) into the template fragments
used by the app's LiveSetExporter (app/src/main/assets/als/).

The reference was produced from scratch (no Ableton content) by DawVert's
Ableton writer from a two-track MIDI file. Placeholders use {{NAME}} syntax;
automation/modulation target ids that must be unique per set become Id="@@"
and are numbered by the exporter.
"""
import re, sys, pathlib

src, out = sys.argv[1], pathlib.Path(sys.argv[2])
lines = pathlib.Path(src).read_text().split('\n')

def find(pattern, start=0):
    for i in range(start, len(lines)):
        if re.search(pattern, lines[i]):
            return i
    raise SystemExit('not found: ' + pattern)

def dedent(block, n):
    return '\n'.join(l[n:] if l.startswith('\t' * n) else l.lstrip('\t') for l in block)

UNIQUE = r'<((?:Automation|Modulation|VolumeModulation|TranspositionModulation|GrainSizeModulation|FluxModulation|SampleOffsetModulation)Target|ControllerTargets\.\d+|Pointee) Id="\d+"'

# ---- head
tracks = find(r'^\t\t<Tracks>')
head = '\n'.join(lines[:tracks + 1])
head = head.replace('<?xml version="1.0" ?>', '<?xml version="1.0" encoding="UTF-8"?>')
head = re.sub(r'<NextPointeeId Value="\d+"/>', '<NextPointeeId Value="{{NEXT_POINTEE_ID}}"/>', head)

# ---- midi track (first MidiTrack)
t0 = find(r'^\t\t\t<MidiTrack ')
t1 = find(r'^\t\t\t</MidiTrack>', t0)
track = lines[t0:t1 + 1]
tx = '\n'.join(track)
tx = re.sub(r'<MidiTrack Id="\d+"', '<MidiTrack Id="{{TRACK_ID}}"', tx, count=1)
tx = re.sub(r'<EffectiveName Value="[^"]*"/>', '<EffectiveName Value="{{NAME}}"/>', tx, count=1)
tx = re.sub(r'<UserName Value="[^"]*"/>', '<UserName Value="{{NAME}}"/>', tx, count=1)
tx = re.sub(r'<Color Value="\d+"/>', '<Color Value="{{COLOR}}"/>', tx, count=1)
tx = re.sub(r'<TrackGroupId Value="-?\d+"/>', '<TrackGroupId Value="-1"/>', tx, count=1)
tx = tx.replace('<Target Value="AudioOut/GroupTrack"/>\n\t\t\t\t\t\t<UpperDisplayString Value="Group"/>',
                '<Target Value="AudioOut/Master"/>\n\t\t\t\t\t\t<UpperDisplayString Value="Master"/>')
# mixer values (first occurrences are the track mixer)
tx = re.sub(r'(<Speaker>\s*<LomId Value="0"/>\s*<Manual Value=")[a-z]+(")', r'\1{{SPEAKER_ON}}\2', tx, count=1)
tx = re.sub(r'(<Pan>\s*<LomId Value="0"/>\s*<Manual Value=")[-0-9.]+(")', r'\1{{PAN}}\2', tx, count=1)
tx = re.sub(r'(<Volume>\s*<LomId Value="0"/>\s*<Manual Value=")[-0-9.]+(")', r'\1{{VOLUME}}\2', tx, count=1)
# main sequencer clip slots + arrangement, freeze sequencer slots
tx = re.sub(r'<ClipSlotList>.*?</ClipSlotList>', '<ClipSlotList>\n{{CLIPSLOTS}}\n\t\t\t\t\t\t</ClipSlotList>', tx, count=1, flags=re.S)
tx = re.sub(r'(<ClipTimeable>\s*<ArrangerAutomation>\s*)<Events>.*?</Events>', r'\1<Events>\n{{ARRANGEMENT_CLIPS}}\n\t\t\t\t\t\t\t\t</Events>', tx, count=1, flags=re.S)
tx = re.sub(r'(<FreezeSequencer>.*?)<ClipSlotList>.*?</ClipSlotList>', r'\1<ClipSlotList>\n{{FREEZE_CLIPSLOTS}}\n\t\t\t\t\t\t</ClipSlotList>', tx, count=1, flags=re.S)
tx = re.sub(UNIQUE, lambda m: '<%s Id="@@"' % m.group(1), tx)
assert '{{CLIPSLOTS}}' in tx and '{{FREEZE_CLIPSLOTS}}' in tx and '{{ARRANGEMENT_CLIPS}}' in tx
assert '{{VOLUME}}' in tx and '{{PAN}}' in tx and '{{SPEAKER_ON}}' in tx

# ---- midi clip
c0 = find(r'<MidiClip Id=', t0)
c1 = find(r'</MidiClip>', c0)
clip = '\n'.join(l[9:] for l in lines[c0:c1 + 1])
def sub1(pat, rep):
    global clip
    new = re.sub(pat, rep, clip, count=1, flags=re.S)
    assert new != clip, pat
    clip = new
sub1(r'<MidiClip Id="\d+" Time="[^"]*">', '<MidiClip Id="{{CLIP_ID}}" Time="{{TIME}}">')
sub1(r'<CurrentStart Value="[^"]*"/>', '<CurrentStart Value="{{CURRENT_START}}"/>')
sub1(r'<CurrentEnd Value="[^"]*"/>', '<CurrentEnd Value="{{CURRENT_END}}"/>')
sub1(r'<LoopEnd Value="[^"]*"/>', '<LoopEnd Value="{{LENGTH}}"/>')
sub1(r'<LoopOn Value="[^"]*"/>', '<LoopOn Value="{{LOOP_ON}}"/>')
sub1(r'<OutMarker Value="[^"]*"/>', '<OutMarker Value="{{LENGTH}}"/>')
sub1(r'<HiddenLoopEnd Value="[^"]*"/>', '<HiddenLoopEnd Value="{{LENGTH}}"/>')
sub1(r'<Name Value="[^"]*"/>', '<Name Value="{{NAME}}"/>')
sub1(r'<Color Value="\d+"/>', '<Color Value="{{COLOR}}"/>')
sub1(r'<KeyTracks>.*?</KeyTracks>', '<KeyTracks>\n{{KEY_TRACKS}}\n\t\t\t</KeyTracks>')
sub1(r'(<NoteIdGenerator>\s*<NextId Value=")\d+', r'\g<1>{{NEXT_NOTE_ID}}')
sub1(r'(<ScaleInformation>\s*<RootNote Value=")\d+("/>\s*<Name Value=")[^"]*', r'\g<1>{{ROOT_NOTE}}\g<2>{{SCALE_NAME}}')
sub1(r'<IsInKey Value="[^"]*"/>', '<IsInKey Value="{{IN_KEY}}"/>')

# ---- scene
s0 = find(r'^\t\t\t<Scene Id=')
s1 = find(r'^\t\t\t</Scene>', s0)
scene = '\n'.join(lines[s0:s1 + 1])
scene = re.sub(r'<Scene Id="\d+">', '<Scene Id="{{SCENE_ID}}">', scene)
scene = re.sub(r'<Name Value="[^"]*"/>', '<Name Value="{{NAME}}"/>', scene)
scene = re.sub(r'<Tempo Value="[^"]*"/>', '<Tempo Value="{{TEMPO}}"/>', scene)

# ---- tail: everything after the last track, scenes replaced
m0 = find(r'^\t\t</Tracks>')
tail = '\n'.join(lines[m0:])
tail = re.sub(r'\t\t<Scenes>.*?</Scenes>', '\t\t<Scenes>\n{{SCENES}}\n\t\t</Scenes>', tail, count=1, flags=re.S)
tail = re.sub(r'(<Tempo>\s*<LomId Value="0"/>\s*<Manual Value=")[0-9.]+', r'\g<1>{{TEMPO}}', tail, count=1)
tail = re.sub(r'(<FloatEvent Id="\d+" Time="[^"]*" Value=")[0-9.]+', r'\g<1>{{TEMPO}}', tail)
tail = re.sub(r'(\t\t<ScaleInformation>\s*<RootNote Value=")\d+("/>\s*<Name Value=")[^"]*', r'\g<1>{{ROOT_NOTE}}\g<2>{{SCALE_NAME}}', tail, count=1)
ids = [int(x) for x in re.findall(r' Id="(\d+)"', tail)]
print('max fixed id in tail:', max(ids))

out.mkdir(parents=True, exist_ok=True)
for name, text in [('head.xml', head), ('miditrack.xml', tx), ('midiclip.xml', clip), ('scene.xml', scene), ('tail.xml', tail)]:
    (out / name).write_text(text + ('\n' if not text.endswith('\n') else ''))
    print(name, len(text))
