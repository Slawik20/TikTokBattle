package ua.tiktokbattle;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.attribute.Attribute;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.util.Vector;
import java.io.File;
import java.io.IOException;
import java.util.*;

public final class TikTokBattle extends JavaPlugin implements Listener, CommandExecutor {
    enum Side { GREEN, PURPLE; Side enemy(){return this==GREEN?PURPLE:GREEN;} }
    record Request(Side side, EntityType type, String viewer) {}
    static final class Unit {
        final UUID id; final Side side; final int lane; final String kind;
        int waypoint=0; long nextCoreHit=0; long lastMoved; Location lastLocation;
        Unit(UUID id,Side side,int lane,String kind,Location at,long tick){this.id=id;this.side=side;this.lane=lane;this.kind=kind;this.lastLocation=at.clone();this.lastMoved=tick;}
    }
    private final EnumMap<Side, ArrayDeque<Request>> queues=new EnumMap<>(Side.class);
    private final Map<UUID,Unit> units=new HashMap<>();
    private final EnumMap<Side,Location> spawns=new EnumMap<>(Side.class);
    private final EnumMap<Side,Location> cores=new EnumMap<>(Side.class);
    private final EnumMap<Side,Integer> maxHp=new EnumMap<>(Side.class), hp=new EnumMap<>(Side.class);
    private final EnumMap<Side,List<List<Location>>> lanes=new EnumMap<>(Side.class);
    private final EnumMap<Side,Integer> laneCursor=new EnumMap<>(Side.class);
    private File arenaFile; private YamlConfiguration arena;
    private NamespacedKey unitKey; private long tick=0, finishAt=0; private boolean running=false;

    @Override public void onEnable(){
        saveDefaultConfig();unitKey=new NamespacedKey(this,"battle_unit");
        for(Side s:Side.values()){
            queues.put(s,new ArrayDeque<>());laneCursor.put(s,0);
            List<List<Location>> paths=new ArrayList<>();for(int i=0;i<3;i++)paths.add(new ArrayList<>());lanes.put(s,paths);
        }
        arenaFile=new File(getDataFolder(),"arena.yml");arena=YamlConfiguration.loadConfiguration(arenaFile);loadArena();
        for(String cmd:List.of("spawnmobgren","spawnmobpurple","spawnblockgr","spawnblockpurple","battlelane","battlespawn","battlestart","battlestop","battlereset","battlestatus"))Objects.requireNonNull(getCommand(cmd)).setExecutor(this);
        getServer().getPluginManager().registerEvents(this,this);
        getServer().getScheduler().runTaskTimer(this,()->{tick++;if(running){processQueues();if(tick%5==0)updateUnits();if(tick>=finishAt)finishByHp();}},1,1);
        getLogger().info("TikTokBattle enabled for Paper 1.20.1");
    }
    @Override public void onDisable(){running=false;removeUnits();saveArena();}
    private void saveArena(){
        for(Side s:Side.values()){
            String p=s.name().toLowerCase();arena.set(p+".spawn",spawns.get(s));arena.set(p+".core",cores.get(s));arena.set(p+".max-hp",maxHp.getOrDefault(s,1000));
            for(int i=0;i<3;i++)arena.set(p+".lanes."+(i+1),lanes.get(s).get(i));
        }
        try{arena.save(arenaFile);}catch(IOException e){getLogger().severe("Cannot save arena.yml: "+e.getMessage());}
    }
    private void loadArena(){
        for(Side s:Side.values()){
            String p=s.name().toLowerCase();Location spawn=arena.getLocation(p+".spawn"),core=arena.getLocation(p+".core");
            if(spawn!=null)spawns.put(s,spawn);if(core!=null)cores.put(s,core);
            maxHp.put(s,arena.getInt(p+".max-hp",1000));hp.put(s,maxHp.get(s));
            for(int i=0;i<3;i++){
                List<?> raw=arena.getList(p+".lanes."+(i+1),List.of());
                for(Object o:raw)if(o instanceof Location l)lanes.get(s).get(i).add(l);
            }
        }
    }
    private Side parseSide(String value){return switch(value.toLowerCase(Locale.ROOT)){case "green","gr","gren"->Side.GREEN;case "purple","pur"->Side.PURPLE;default->null;};}
    private void msg(CommandSender s,String text){s.sendMessage(ChatColor.GOLD+"[Battle] "+ChatColor.WHITE+text);}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!sender.hasPermission("battle.admin")){msg(sender,"No permission");return true;}
        String name=command.getName().toLowerCase(Locale.ROOT);
        if(name.equals("battlestatus")){for(Side side:Side.values())msg(sender,side+" HP "+hp.get(side)+"/"+maxHp.get(side)+" | mobs "+active(side)+"/"+limit()+" | queued "+queues.get(side).size());msg(sender,"Match running: "+running);return true;}
        if(name.equals("battlestop")){running=false;msg(sender,"Match stopped; active units remain until reset.");return true;}
        if(name.equals("battlereset")){reset();msg(sender,"Arena reset; all queues cleared.");return true;}
        if(name.equals("battlestart")){
            if(running){msg(sender,"Already running.");return true;}
            for(Side side:Side.values()){
                if(spawns.get(side)==null||cores.get(side)==null){msg(sender,"Missing spawn or core for "+side);return true;}
                for(int i=0;i<3;i++)if(lanes.get(side).get(i).isEmpty()){msg(sender,"Missing lane "+(i+1)+" for "+side);return true;}
            }
            reset();running=true;finishAt=tick+20L*getConfig().getInt("match-duration-seconds",300);msg(sender,"Battle started!");return true;
        }
        if(name.equals("battlespawn")){
            if(!running){msg(sender,"Start a match with /battlestart first.");return true;}
            if(args.length!=3){msg(sender,"Usage: /battlespawn <green|purple> <mob> <count>");return true;}
            Side side=parseSide(args[0]);if(side==null){msg(sender,"Invalid team");return true;}
            EntityType type;try{type=EntityType.valueOf(args[1].toUpperCase(Locale.ROOT));}catch(IllegalArgumentException e){msg(sender,"Unknown mob");return true;}
            if(!type.isAlive()||!type.isSpawnable()||type==EntityType.PLAYER){msg(sender,"Not a spawnable living mob");return true;}
            int count;try{count=Integer.parseInt(args[2]);}catch(NumberFormatException e){msg(sender,"Invalid count");return true;}
            if(count<1||count>500){msg(sender,"Count must be 1..500");return true;}
            for(int i=0;i<count;i++)queues.get(side).addLast(new Request(side,type,"Admin"));
            msg(sender,"Queued "+count+" "+type+" for "+side);return true;
        }
        if(!(sender instanceof Player player)){msg(sender,"This command must be run in-game.");return true;}
        if(name.equals("spawnmobgren")||name.equals("spawnmobpurple")){
            Side side=name.equals("spawnmobgren")?Side.GREEN:Side.PURPLE;spawns.put(side,player.getLocation().clone());saveArena();msg(sender,"Spawn set for "+side);return true;
        }
        if(name.equals("spawnblockgr")||name.equals("spawnblockpurple")){
            if(args.length!=1){msg(sender,"Usage: /"+name+" <hp>");return true;}
            int value;try{value=Integer.parseInt(args[0]);}catch(NumberFormatException e){msg(sender,"HP must be a number");return true;}
            if(value<1||value>1000000){msg(sender,"HP must be 1..1000000");return true;}
            Block block=player.getTargetBlockExact(10);
            if(block==null||block.getType().isAir()){msg(sender,"Look at a solid block within 10 blocks");return true;}
            Side side=name.equals("spawnblockgr")?Side.GREEN:Side.PURPLE;
            if(isCore(block) && !block.getLocation().equals(cores.get(side))){msg(sender,"That block is already the opposing core.");return true;}
            cores.put(side,block.getLocation());maxHp.put(side,value);hp.put(side,value);saveArena();msg(sender,side+" core set: "+value+" HP");return true;
        }
        if(name.equals("battlelane")){
            if(args.length!=3){msg(sender,"Usage: /battlelane <team> <1|2|3> <add|clear>");return true;}
            Side side=parseSide(args[0]);int index;try{index=Integer.parseInt(args[1])-1;}catch(NumberFormatException e){index=-1;}
            if(side==null||index<0||index>2){msg(sender,"Invalid team or lane");return true;}
            List<Location> path=lanes.get(side).get(index);
            if(args[2].equalsIgnoreCase("clear"))path.clear();else if(args[2].equalsIgnoreCase("add"))path.add(player.getLocation().clone());else{msg(sender,"Use add or clear");return true;}
            saveArena();msg(sender,side+" lane "+(index+1)+" now has "+path.size()+" waypoints");return true;
        }
        return true;
    }
    private int limit(){return Math.min(50,Math.max(1,getConfig().getInt("max-mobs-per-team",50)));}
    private int active(Side side){int n=0;for(Unit u:units.values())if(u.side==side)n++;return n;}
    private void processQueues(){
        int interval=Math.max(1,getConfig().getInt("spawn-interval-ticks",10));if(tick%interval!=0)return;
        for(Side side:Side.values()){
            if(active(side)>=limit()||units.size()>=100)continue;
            Request req=queues.get(side).peekFirst();if(req==null)continue;
            Location at=spawns.get(side);if(at==null||at.getWorld()==null)continue;
            try{
                Entity e=at.getWorld().spawnEntity(at,req.type());
                if(!(e instanceof LivingEntity living)){e.remove();continue;}
                int lane=laneCursor.get(side)%3;laneCursor.put(side,laneCursor.get(side)+1);
                living.getPersistentDataContainer().set(unitKey,PersistentDataType.STRING,side.name());
                living.setCustomName((side==Side.GREEN?ChatColor.GREEN:ChatColor.LIGHT_PURPLE)+req.viewer()+" • "+req.type());
                living.setCustomNameVisible(true);living.setRemoveWhenFarAway(false);
                if(living instanceof Mob mob)mob.setTarget(null);
                units.put(living.getUniqueId(),new Unit(living.getUniqueId(),side,lane,req.type().name(),at,tick));
                queues.get(side).removeFirst();
            }catch(Exception ex){getLogger().warning("Spawn failed for "+req.type()+": "+ex.getMessage());}
        }
    }
    private void updateUnits(){
        for(Unit unit:new ArrayList<>(units.values())){
            Entity raw=Bukkit.getEntity(unit.id);
            if(!(raw instanceof LivingEntity entity)||entity.isDead()||!entity.isValid()){units.remove(unit.id);continue;}
            Location pos=entity.getLocation();
            if(pos.getWorld()==unit.lastLocation.getWorld()&&pos.distanceSquared(unit.lastLocation)>1){unit.lastMoved=tick;unit.lastLocation=pos.clone();}
            LivingEntity target=nearestEnemy(unit,pos);
            if(target!=null){
                if(entity instanceof Mob mob)mob.setTarget(target);
                else moveToward(entity,target.getLocation());
                continue;
            }
            if(entity instanceof Mob mob)mob.setTarget(null);
            Location core=cores.get(unit.side.enemy());if(core==null||core.getWorld()!=pos.getWorld())continue;
            Location coreCenter=core.clone().add(.5,.5,.5);
            double attackRange=getConfig().getDouble("core-attack-range",2.8);
            if(pos.distanceSquared(coreCenter)<=attackRange*attackRange){
                if(tick>=unit.nextCoreHit){damageCore(unit.side.enemy(),coreDamage(unit.kind));unit.nextCoreHit=tick+Math.max(10,getConfig().getInt("core-attack-interval-ticks",40));}
                continue;
            }
            List<Location> path=lanes.get(unit.side).get(unit.lane);
            Location destination=coreCenter;
            while(unit.waypoint<path.size()){
                Location waypoint=path.get(unit.waypoint);
                if(waypoint.getWorld()!=pos.getWorld())break;
                if(pos.distanceSquared(waypoint)<4)unit.waypoint++;else{destination=waypoint;break;}
            }
            moveToward(entity,destination);
            if(tick-unit.lastMoved>20L*getConfig().getInt("mob-stuck-seconds",12)){
                Location safe=destination.clone();if(safe.getWorld()==pos.getWorld()&&safe.distanceSquared(pos)<64){entity.teleport(safe);unit.lastMoved=tick;unit.lastLocation=safe;}
            }
        }
    }
    private LivingEntity nearestEnemy(Unit unit,Location pos){
        double range=getConfig().getDouble("mob-target-range",9);double best=range*range;LivingEntity found=null;
        for(Entity other:pos.getWorld().getNearbyEntities(pos,range,range,range)){
            Unit rival=units.get(other.getUniqueId());if(rival==null||rival.side==unit.side||!(other instanceof LivingEntity living)||living.isDead())continue;
            double d=other.getLocation().distanceSquared(pos);if(d<best){best=d;found=living;}
        }
        return found;
    }
    private void moveToward(LivingEntity entity,Location goal){
        if(entity.getWorld()!=goal.getWorld())return;
        Vector direction=goal.toVector().subtract(entity.getLocation().toVector());direction.setY(0);
        if(direction.lengthSquared()<.09)return;
        Vector velocity=direction.normalize().multiply(.18);velocity.setY(entity.getVelocity().getY());entity.setVelocity(velocity);
    }
    private int coreDamage(String kind){return switch(kind){case "IRON_GOLEM"->10;case "RAVAGER"->15;case "WARDEN"->25;case "SKELETON"->3;default->2;};}
    private void damageCore(Side victim,int amount){
        if(!running)return;hp.put(victim,Math.max(0,hp.get(victim)-amount));
        Location core=cores.get(victim);if(core!=null&&core.getWorld()!=null)core.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,core.clone().add(.5,1,.5),4,.3,.3,.3,.1);
        if(hp.get(victim)==0)endMatch(victim.enemy()+" wins!");
    }
    private void finishByHp(){int a=hp.get(Side.GREEN),b=hp.get(Side.PURPLE);endMatch(a==b?"Draw!":(a>b?"GREEN":"PURPLE")+" wins on HP!");}
    private void endMatch(String message){running=false;Bukkit.broadcastMessage(ChatColor.GOLD+"[Battle] "+message);removeUnits();for(var q:queues.values())q.clear();}
    private void removeUnits(){for(UUID id:new ArrayList<>(units.keySet())){Entity e=Bukkit.getEntity(id);if(e!=null)e.remove();}units.clear();}
    private void reset(){running=false;removeUnits();for(var q:queues.values())q.clear();for(Side side:Side.values()){hp.put(side,maxHp.getOrDefault(side,1000));laneCursor.put(side,0);}}
    @EventHandler public void onDeath(EntityDeathEvent event){Unit unit=units.remove(event.getEntity().getUniqueId());if(unit!=null){event.getDrops().clear();event.setDroppedExp(0);}}
    @EventHandler public void onDamage(EntityDamageByEntityEvent event){
        Unit victim=units.get(event.getEntity().getUniqueId());
        Entity attacker=event.getDamager();if(attacker instanceof Projectile projectile&&projectile.getShooter() instanceof Entity shooter)attacker=shooter;
        Unit source=units.get(attacker.getUniqueId());
        if(source!=null && (victim==null || source.side==victim.side))event.setCancelled(true);
    }
    @EventHandler public void onExplosion(EntityExplodeEvent event){if(running && event.getEntity()!=null && units.containsKey(event.getEntity().getUniqueId()))event.blockList().clear();}
    @EventHandler public void onBlockBreak(BlockBreakEvent event){if(isCore(event.getBlock()))event.setCancelled(true);}
    @EventHandler public void onBlockPlace(BlockPlaceEvent event){if(isCore(event.getBlock()))event.setCancelled(true);}
    private boolean isCore(Block block){for(Location loc:cores.values())if(loc!=null&&loc.getWorld()==block.getWorld()&&loc.getBlockX()==block.getX()&&loc.getBlockY()==block.getY()&&loc.getBlockZ()==block.getZ())return true;return false;}
}
