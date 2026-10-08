from pathlib import Path
import re,subprocess
r=Path(__file__).resolve().parent
s=(r/'src/main/java/ua/tiktokbattle/TikTokBattle.java').read_text(); p=(r/'src/main/resources/plugin.yml').read_text(); w=(r/'.github/workflows/build.yml').read_text()
for c in ['spawnmobgren','spawnmobpurple','spawnblockgr','spawnblockpurple','battlelane','battlespawn','battleboss','battlestart','battlestop','battlereset','battlestatus']:
 assert re.search(r'^  '+c+r':',p,re.M),c
 assert '"'+c+'"' in s,c
assert 'safe.getBlock().isPassable()' in s
assert 'pos.distanceSquared(target.getLocation())>range*range' in s
assert '!entity.hasLineOfSight(target)' in s
assert 'entity.hasLineOfSight(coreCenter)' in s
assert 'queues.get(side).size()+count>2000' in s
assert 'mob.setAI(false)' in s
assert 'Avoid spawning summoned helpers inside solid blocks' in s
assert 'Math.min(5,enemies.size())' in s
assert 'Math.min(limit()-active(boss.side),100-units.size())' in s
assert 'Math.abs(pos.getY()-target.getLocation().getY())>2.0' in s
assert 'd.setTeleportDuration(5)' in s
for number in range(8101,8112):
 assert str(number) in s, f'Missing Java model ID {number}'
assert 'gradle clean build' in w and 'upload-artifact' in w
subprocess.run(['python3',str(r/'validate_assets.py')],check=True)
print('PASS: commands, safe teleport, CI configuration')
