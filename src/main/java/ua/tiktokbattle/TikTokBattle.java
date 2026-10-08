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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.bukkit.boss.*;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import java.io.File;
import java.io.IOException;
import java.util.*;

public final class TikTokBattle extends JavaPlugin implements Listener, CommandExecutor {
    enum Side { GREEN, PURPLE; Side enemy(){return this==GREEN?PURPLE:GREEN;} }
    record Request(Side side, EntityType type, String viewer, String boss) {}
    static final class Unit {
        final UUID id; final Side side; final int lane; final String kind;
        int waypoint=0; long nextCoreHit=0; long nextEnemyHit=0; long lastMoved; long nextSkill=0; Location lastLocation; String boss=null; UUID owner=null;
        Unit(UUID id,Side side,int lane,String kind,Location at,long tick){this.id=id;this.side=side;this.lane=lane;this.kind=kind;this.lastLocation=at.clone();this.lastMoved=tick;}
    }
    private final EnumMap<Side, ArrayDeque<Request>> queues=new EnumMap<>(Side.class);
    private final Map<UUID,Unit> units=new HashMap<>();
    private final Map<UUID,UUID> visuals=new HashMap<>();
    private final Map<UUID,Long> visualAttacks=new HashMap<>();
    private final EnumMap<Side,Location> spawns=new EnumMap<>(Side.class);
    private final EnumMap<Side,Location> cores=new EnumMap<>(Side.class);
    private final EnumMap<Side,Integer> maxHp=new EnumMap<>(Side.class), hp=new EnumMap<>(Side.class);
    private final EnumMap<Side,List<List<Location>>> lanes=new EnumMap<>(Side.class);
    private final EnumMap<Side,Integer> laneCursor=new EnumMap<>(Side.class);
    private final EnumMap<Side,BossBar> bars=new EnumMap<>(Side.class);
    private File arenaFile; private YamlConfiguration arena;
    private NamespacedKey unitKey; private long tick=0, finishAt=0; private boolean running=false;

    @Override public void onEnable(){
        saveDefaultConfig();unitKey=new NamespacedKey(this,"battle_unit");
        for(Side s:Side.values()){
            queues.put(s,new ArrayDeque<>());laneCursor.put(s,0);
            List<List<Location>> paths=new ArrayList<>();for(int i=0;i<3;i++)paths.add(new ArrayList<>());lanes.put(s,paths);
        }
        for(Side side:Side.values())bars.put(side,Bukkit.createBossBar("",side==Side.GREEN?BarColor.GREEN:BarColor.PURPLE,BarStyle.SOLID));
        arenaFile=new File(getDataFolder(),"arena.yml");arena=YamlConfiguration.loadConfiguration(arenaFile);loadArena();updateBars();
        for(Player player:Bukkit.getOnlinePlayers())showBars(player);
        for(String cmd:List.of("spawnmobgren","spawnmobpurple","spawnblockgr","spawnblockpurple","battlelane","battlespawn","battleboss","battlestart","battlestop","battlereset","battlestatus"))Objects.requireNonNull(getCommand(cmd)).setExecutor(this);
        getServer().getPluginManager().registerEvents(this,this);
        getServer().getScheduler().runTaskTimer(this,()->{tick++;if(running){processQueues();if(tick%5==0)updateUnits();if(tick>=finishAt)finishByHp();}if(tick%5==0&&!visuals.isEmpty())updateVisuals();},1,1);
        getLogger().info("TikTokBattle enabled для Paper 1.20.1");
    }
    @Override public void onDisable(){running=false;removeUnits();saveArena();for(BossBar bar:bars.values())bar.removeAll();}
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
    private void msg(CommandSender s,String text){s.sendMessage(ChatColor.GOLD+"[Битва] "+ChatColor.WHITE+text);}
    private String sideName(Side side){return side==Side.GREEN?"ЗЕЛЕНІ":"ФІОЛЕТОВІ";}
    private void showBars(Player player){for(BossBar bar:bars.values())bar.addPlayer(player);}
    private void updateBars(){for(Side side:Side.values()){BossBar bar=bars.get(side);if(bar==null)continue;int maximum=Math.max(1,maxHp.getOrDefault(side,1000)),current=hp.getOrDefault(side,maximum);bar.setTitle((side==Side.GREEN?ChatColor.GREEN:ChatColor.LIGHT_PURPLE)+sideName(side)+" | Ядро: "+current+" / "+maximum+" HP");bar.setProgress(Math.max(0,Math.min(1,(double)current/maximum)));}}
    @EventHandler public void onJoin(org.bukkit.event.player.PlayerJoinEvent event){showBars(event.getPlayer());}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!sender.hasPermission("battle.admin")){msg(sender,"Немає дозволу");return true;}
        String name=command.getName().toLowerCase(Locale.ROOT);
        if(name.equals("battlestatus")){for(Side side:Side.values())msg(sender,sideName(side)+" HP "+hp.get(side)+"/"+maxHp.get(side)+" | мобів "+active(side)+"/"+limit()+" | черга "+queues.get(side).size());msg(sender,"Битва триває: "+(running?"так":"ні"));return true;}
        if(name.equals("battlestop")){running=false;msg(sender,"Битву зупинено. Активні моби залишаться до скидання.");return true;}
        if(name.equals("battlereset")){reset();msg(sender,"Арену скинуто, черги очищено.");return true;}
        if(name.equals("battlestart")){
            if(running){msg(sender,"Битва вже триває.");return true;}
            for(Side side:Side.values()){
                if(spawns.get(side)==null||cores.get(side)==null){msg(sender,"Не встановлено спавн або ядро команди "+sideName(side));return true;}
                for(int i=0;i<3;i++)if(lanes.get(side).get(i).isEmpty()){msg(sender,"Не налаштовано маршрут "+(i+1)+" для "+sideName(side));return true;}
            }
            reset();running=true;finishAt=tick+20L*getConfig().getInt("match-duration-seconds",300);msg(sender,"Битву розпочато!");return true;
        }
        if(name.equals("battleboss")){
            if(!running){msg(sender,"Спочатку запусти битву командою /battlestart.");return true;}
            if(args.length!=2){msg(sender,"Використання: /battleboss <green|purple> <necromancer|witch|poseidon>");return true;}
            Side side=parseSide(args[0]);if(side==null){msg(sender,"Невідома команда.");return true;}
            String boss=args[1].toLowerCase(Locale.ROOT);
            EntityType type=switch(boss){case "necromancer"->EntityType.EVOKER;case "witch"->EntityType.WITCH;case "poseidon"->EntityType.DROWNED;default->null;};
            if(type==null){msg(sender,"Невідомий бос. Доступні: necromancer, witch, poseidon");return true;}
            if(queues.get(side).size()>=2000){msg(sender,"Черга заповнена (2000). Спробуй пізніше.");return true;}
            queues.get(side).addLast(new Request(side,type,"Бос",boss));
            msg(sender,"Боса додано до черги: "+boss+" для "+sideName(side));return true;
        }
        if(name.equals("battlespawn")){
            if(!running){msg(sender,"Спочатку запусти битву командою /battlestart.");return true;}
            if(args.length!=3){msg(sender,"Використання: /battlespawn <green|purple> <моб> <кількість>");return true;}
            Side side=parseSide(args[0]);if(side==null){msg(sender,"Невідома команда");return true;}
            EntityType type;try{type=EntityType.valueOf(args[1].toUpperCase(Locale.ROOT));}catch(IllegalArgumentException e){msg(sender,"Невідомий тип моба");return true;}
            if(!type.isAlive()||!type.isSpawnable()||type==EntityType.PLAYER){msg(sender,"Цей тип моба не можна створити");return true;}
            int count;try{count=Integer.parseInt(args[2]);}catch(NumberFormatException e){msg(sender,"Невірна кількість");return true;}
            if(count<1||count>500){msg(sender,"Кількість має бути від 1 до 500");return true;}
            if(queues.get(side).size()+count>2000){msg(sender,"Черга переповниться. Максимум 2000 заявок на команду.");return true;}
            for(int i=0;i<count;i++)queues.get(side).addLast(new Request(side,type,"Адмін",null));
            msg(sender,"Додано до черги: "+count+" "+type+" для "+sideName(side));return true;
        }
        if(!(sender instanceof Player player)){msg(sender,"Ця команда доступна лише в грі.");return true;}
        if(name.equals("spawnmobgren")||name.equals("spawnmobpurple")){
            Side side=name.equals("spawnmobgren")?Side.GREEN:Side.PURPLE;spawns.put(side,player.getLocation().clone());saveArena();msg(sender,"Точку появи встановлено для "+sideName(side));return true;
        }
        if(name.equals("spawnblockgr")||name.equals("spawnblockpurple")){
            if(args.length!=1){msg(sender,"Використання: /"+name+" <hp>");return true;}
            int value;try{value=Integer.parseInt(args[0]);}catch(NumberFormatException e){msg(sender,"HP має бути числом");return true;}
            if(value<1||value>1000000){msg(sender,"HP має бути від 1 до 1000000");return true;}
            Block block=player.getTargetBlockExact(10);
            if(block==null||block.getType().isAir()){msg(sender,"Подивись на твердий блок на відстані до 10 блоків");return true;}
            Side side=name.equals("spawnblockgr")?Side.GREEN:Side.PURPLE;
            if(isCore(block) && !block.getLocation().equals(cores.get(side))){msg(sender,"Цей блок уже є ядром іншої команди.");return true;}
            cores.put(side,block.getLocation());maxHp.put(side,value);hp.put(side,value);updateBars();saveArena();msg(sender,sideName(side)+" — ядро встановлено: "+value+" HP");return true;
        }
        if(name.equals("battlelane")){
            if(args.length!=3){msg(sender,"Використання: /battlelane <team> <1|2|3> <add|clear>");return true;}
            Side side=parseSide(args[0]);int index;try{index=Integer.parseInt(args[1])-1;}catch(NumberFormatException e){index=-1;}
            if(side==null||index<0||index>2){msg(sender,"Невірна команда або номер маршруту");return true;}
            List<Location> path=lanes.get(side).get(index);
            if(args[2].equalsIgnoreCase("clear"))path.clear();else if(args[2].equalsIgnoreCase("add"))path.add(player.getLocation().clone());else{msg(sender,"Використай add або clear");return true;}
            saveArena();msg(sender,sideName(side)+" — маршрут "+(index+1)+" тепер має "+path.size()+" точок");return true;
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
                if(living instanceof Mob mob){mob.setTarget(null);mob.setAI(false);}
                Unit unit=new Unit(living.getUniqueId(),side,lane,req.type().name(),at,tick);
                if(req.boss()!=null){unit.boss=req.boss();unit.nextSkill=tick+600;applyBoss(living,unit);}
                units.put(living.getUniqueId(),unit);
                attachVisual(living,unit);
                queues.get(side).removeFirst();
            }catch(Exception ex){getLogger().warning("Spawn failed для "+req.type()+": "+ex.getMessage());}
        }
    }
    private void updateUnits(){
        for(Unit unit:new ArrayList<>(units.values())){
            Entity raw=Bukkit.getEntity(unit.id);
            if(!(raw instanceof LivingEntity entity)||entity.isDead()||!entity.isValid()){units.remove(unit.id);removeVisual(unit.id);continue;}
            Location pos=entity.getLocation();
            if(unit.boss!=null&&tick>=unit.nextSkill){useBossSkill(entity,unit);unit.nextSkill=tick+600;}
            if(pos.getWorld()==unit.lastLocation.getWorld()&&pos.distanceSquared(unit.lastLocation)>1){unit.lastMoved=tick;unit.lastLocation=pos.clone();}
            LivingEntity target=nearestEnemy(unit,pos);
            if(target!=null){
                if(entity instanceof Mob mob)mob.setTarget(null);
                double range=unit.kind.equals("SKELETON")||unit.kind.equals("STRAY")?8.0:2.2;
                if(pos.distanceSquared(target.getLocation())>range*range || Math.abs(pos.getY()-target.getLocation().getY())>2.0 || !entity.hasLineOfSight(target)){
                    moveToward(entity,target.getLocation());
                }
                else if(tick>=unit.nextEnemyHit){
                    // Damage is applied by the arena controller: vanilla AI is disabled.
                    // This also prevents friendly fire and makes combat deterministic.
                    target.damage(unit.boss!=null?12.0:unit.kind.equals("IRON_GOLEM")?9.0:4.0,entity);
                    visualAttacks.put(unit.id,tick);
                    unit.nextEnemyHit=tick+Math.max(10,getConfig().getInt("combat.attack-cooldown-ticks",24));
                }
                continue;
            }
            if(entity instanceof Mob mob)mob.setTarget(null);
            Location core=cores.get(unit.side.enemy());if(core==null||core.getWorld()!=pos.getWorld())continue;
            Location coreCenter=core.clone().add(.5,.5,.5);
            double attackRange=getConfig().getDouble("core-attack-range",2.8);
            if(pos.distanceSquared(coreCenter)<=attackRange*attackRange && entity.hasLineOfSight(coreCenter)){
                if(tick>=unit.nextCoreHit){damageCore(unit.side.enemy(),coreDamage(unit.kind));unit.nextCoreHit=tick+Math.max(10,getConfig().getInt("core-attack-interval-ticks",40));}
                continue;
            }
            List<Location> path=lanes.get(unit.side).get(unit.lane);
            Location destination=coreCenter;
            while(unit.waypoint<path.size()){
                Location waypoint=path.get(unit.waypoint);
                if(waypoint.getWorld()!=pos.getWorld())break;
                if(horizontalDistanceSquared(pos,waypoint)<1.25)unit.waypoint++;else{destination=waypoint;break;}
            }
            moveToward(entity,destination);
            if(tick-unit.lastMoved>20L*getConfig().getInt("mob-stuck-seconds",12)){
                Location safe=destination.clone();if(safe.getWorld()==pos.getWorld()&&safe.distanceSquared(pos)<64&&safe.getBlock().isPassable()&&safe.clone().add(0,1,0).getBlock().isPassable()&&safe.clone().add(0,-1,0).getBlock().getType().isSolid()){entity.teleport(safe);unit.lastMoved=tick;unit.lastLocation=safe;}
            }
        }
    }
    private LivingEntity nearestEnemy(Unit unit,Location pos){
        double range=getConfig().getDouble("mob-target-range",9);double best=range*range;LivingEntity found=null;
        for(Entity other:pos.getWorld().getNearbyEntities(pos,range,range,range)){
            Unit rival=units.get(other.getUniqueId());if(rival==null||rival.side==unit.side||rival.lane!=unit.lane||!(other instanceof LivingEntity living)||living.isDead())continue;
            double d=other.getLocation().distanceSquared(pos);if(d<best){best=d;found=living;}
        }
        return found;
    }
    private double horizontalDistanceSquared(Location a,Location b){if(a.getWorld()!=b.getWorld())return Double.MAX_VALUE;double dx=a.getX()-b.getX(),dz=a.getZ()-b.getZ();return dx*dx+dz*dz;}
    private void moveToward(LivingEntity entity,Location goal){
        if(entity.getWorld()!=goal.getWorld())return;
        Vector direction=goal.toVector().subtract(entity.getLocation().toVector());direction.setY(0);
        if(direction.lengthSquared()<.09)return;
        Vector velocity=direction.normalize().multiply(.22);velocity.setY(entity.getVelocity().getY());entity.setVelocity(velocity);
    }

    private void applyBoss(LivingEntity entity,Unit unit){
        String title=switch(unit.boss){case "necromancer"->"Некромант";case "witch"->"Стародавня Відьма";default->"Посейдон";};
        double health=switch(unit.boss){case "necromancer"->600;case "witch"->900;default->1500;};
        var attribute=entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if(attribute!=null)attribute.setBaseValue(health);
        entity.setHealth(Math.min(health,entity.getAttribute(Attribute.GENERIC_MAX_HEALTH)==null?health:entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()));
        entity.setCustomName((unit.side==Side.GREEN?ChatColor.GREEN:ChatColor.LIGHT_PURPLE)+"★ "+title);
        entity.setCustomNameVisible(true);
        entity.setRemoveWhenFarAway(false);
    }
    private void useBossSkill(LivingEntity entity,Unit boss){
        if(!running)return;
        if(getConfig().getBoolean("boss-effects.enabled",true)) {
            Particle effect=boss.boss.equals("necromancer")?Particle.SOUL:boss.boss.equals("witch")?Particle.SPELL_WITCH:Particle.WATER_SPLASH;
            entity.getWorld().spawnParticle(effect,entity.getLocation().add(0,1.5,0),Math.min(12,Math.max(0,getConfig().getInt("boss-effects.particles",8))),0.6,0.6,0.6,0.03);
        }
        if(boss.boss.equals("poseidon")){
            List<LivingEntity> enemies=new ArrayList<>();
            for(Unit other:units.values()){
                if(other.side==boss.side)continue;
                Entity target=Bukkit.getEntity(other.id);
                if(target instanceof LivingEntity living&&!living.isDead()&&living.getWorld()==entity.getWorld()&&living.getLocation().distanceSquared(entity.getLocation())<=24*24)enemies.add(living);
            }
            enemies.sort(Comparator.comparingDouble(e->e.getLocation().distanceSquared(entity.getLocation())));
            for(int i=0;i<Math.min(5,enemies.size());i++){
                LivingEntity victim=enemies.get(i);
                victim.getWorld().strikeLightningEffect(victim.getLocation());
                victim.damage(15,entity);
            }
            return;
        }
        int maximum=boss.boss.equals("necromancer")?4:2;
        EntityType type=boss.boss.equals("necromancer")?EntityType.WOLF:EntityType.IRON_GOLEM;
        int alive=0;
        for(Unit other:units.values())if(boss.id.equals(other.owner))alive++;
        int capacity=Math.min(limit()-active(boss.side),100-units.size());
        int toSpawn=Math.min(Math.max(0,maximum-alive),Math.max(0,capacity));
        for(int i=0;i<toSpawn;i++){
            Location at=entity.getLocation().clone().add((i%2==0?1:-1)*1.1,0,(i/2)*1.1);
            // Avoid spawning summoned helpers inside solid blocks or over a void.
            if(!at.getBlock().isPassable() || !at.clone().add(0,1,0).getBlock().isPassable()
                    || !at.clone().add(0,-1,0).getBlock().getType().isSolid())continue;
            Entity spawned=at.getWorld().spawnEntity(at,type);
            if(!(spawned instanceof LivingEntity helper)){spawned.remove();continue;}
            helper.getPersistentDataContainer().set(unitKey,PersistentDataType.STRING,boss.side.name());
            helper.setRemoveWhenFarAway(false);
            helper.setCustomName((boss.side==Side.GREEN?ChatColor.GREEN:ChatColor.LIGHT_PURPLE)+(type==EntityType.WOLF?"Вовк Арса":"Мініголем"));
            helper.setCustomNameVisible(true);
            if(helper instanceof Mob mob){mob.setTarget(null);mob.setAI(false);}
            if(helper instanceof Wolf wolf){wolf.setAdult();wolf.setAngry(false);}
            if(type==EntityType.IRON_GOLEM){var max=helper.getAttribute(Attribute.GENERIC_MAX_HEALTH);if(max!=null){max.setBaseValue(30);helper.setHealth(30);}}
            Unit minion=new Unit(helper.getUniqueId(),boss.side,boss.lane,type.name(),at,tick);
            minion.owner=boss.id;
            minion.waypoint=boss.waypoint;
            units.put(helper.getUniqueId(),minion);
            attachVisual(helper,minion);
        }
    }

    private int coreDamage(String kind){return switch(kind){case "IRON_GOLEM"->10;case "RAVAGER"->15;case "WARDEN"->25;case "SKELETON"->3;default->2;};}
    private void damageCore(Side victim,int amount){
        if(!running)return;hp.put(victim,Math.max(0,hp.get(victim)-amount));updateBars();
        Location core=cores.get(victim);if(core!=null&&core.getWorld()!=null)core.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,core.clone().add(.5,1,.5),4,.3,.3,.3,.1);
        if(hp.get(victim)==0)endMatch(sideName(victim.enemy())+" перемогли!");
    }
    private void finishByHp(){int a=hp.get(Side.GREEN),b=hp.get(Side.PURPLE);endMatch(a==b?"Нічия!":sideName(a>b?Side.GREEN:Side.PURPLE)+" перемогли за HP!");}
    private void endMatch(String message){running=false;Bukkit.broadcastMessage(ChatColor.GOLD+"[Битва] "+message);removeUnits();for(var q:queues.values())q.clear();}
    private void removeUnits(){for(UUID id:new ArrayList<>(units.keySet())){Entity e=Bukkit.getEntity(id);if(e!=null)e.remove();removeVisual(id);}units.clear();for(UUID id:new ArrayList<>(visuals.keySet()))removeVisual(id);}

    // Lightweight client-rendered custom models: one ItemDisplay per fighter.
    // A client must accept the bundled resource pack to see these models.
    private int modelId(Unit unit){
        if(unit.owner!=null)return unit.kind.equals("WOLF")?8110:8111;
        if(unit.boss!=null)return switch(unit.boss){case "necromancer"->8107;case "witch"->8108;case "poseidon"->8109;default->8101;};
        return switch(unit.kind){
            case "SKELETON","STRAY"->8102;
            case "HUSK","PILLAGER","VINDICATOR"->8103;
            case "WITCH","EVOKER"->8104;
            case "IRON_GOLEM","RAVAGER"->8105;
            case "WITHER_SKELETON"->8106;
            case "DROWNED"->8109;
            default->8101;
        };
    }
    private void attachVisual(LivingEntity entity,Unit unit){
        // Disabled by default until clients have installed the resource pack.
        if(!getConfig().getBoolean("models.enabled",false))return;
        try{
            ItemStack item=new ItemStack(Material.CARROT_ON_A_STICK);
            ItemMeta meta=item.getItemMeta();meta.setCustomModelData(modelId(unit));item.setItemMeta(meta);
            ItemDisplay display=entity.getWorld().spawn(entity.getLocation().clone().add(0,getConfig().getDouble("models.y-offset",1.0),0),ItemDisplay.class,d->{
                d.setItemStack(item);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setTeleportDuration(5);
                d.setInterpolationDuration(5);
                d.setPersistent(false);
                d.setInvulnerable(true);
                d.setGravity(false);
            });
            visuals.put(entity.getUniqueId(),display.getUniqueId());
            // Keep the real mob for hitboxes and damage; movement is velocity-driven (vanilla AI is disabled).
            entity.setInvisible(true);
        }catch(Exception ex){removeVisual(entity.getUniqueId());getLogger().warning("Не вдалося створити модель: "+ex.getMessage());}
    }
    private void updateVisuals(){
        for(var entry:new ArrayList<>(visuals.entrySet())){
            Entity fighter=Bukkit.getEntity(entry.getKey());Entity visual=Bukkit.getEntity(entry.getValue());
            if(fighter==null||!fighter.isValid()||visual==null||!visual.isValid()){
                removeVisual(entry.getKey());
                // Restore the original mob if its visual disappeared (e.g. plugin reload).
                if(fighter instanceof LivingEntity living && fighter.isValid())living.setInvisible(false);
                continue;
            }
            Location loc=fighter.getLocation().clone().add(0,getConfig().getDouble("models.y-offset",1.0),0);
            if(loc.getWorld()!=visual.getWorld())continue;
            // Server-light animation: one display, updated only every 5 ticks.
            // Bob when moving, tilt briefly after dealing damage, and lean when hurt.
            Unit unit=units.get(entry.getKey());
            if(unit==null)continue;
            double moved=fighter.getVelocity().getX()*fighter.getVelocity().getX()+fighter.getVelocity().getZ()*fighter.getVelocity().getZ();
            boolean walking=moved>0.0025;
            float bob=walking?(float)(Math.sin(tick*0.42+entry.getKey().hashCode()%13)*getConfig().getDouble("models.walk-bob",0.075)):0f;
            boolean attacking=tick-visualAttacks.getOrDefault(entry.getKey(),-100L)<8;
            float tilt=attacking?(float)Math.toRadians(13):0f;
            if(fighter instanceof LivingEntity living && living.getNoDamageTicks()>living.getMaximumNoDamageTicks()/2)tilt-=(float)Math.toRadians(9);
            if(visual instanceof ItemDisplay display){
                display.setTransformation(new Transformation(new Vector3f(0,bob,0),new AxisAngle4f(tilt,1,0,0),new Vector3f(1,1,1),new AxisAngle4f()));
            }
            loc.setYaw(fighter.getLocation().getYaw());
            visual.teleport(loc);
        }
    }
    private void removeVisual(UUID fighterId){
        visualAttacks.remove(fighterId);
        UUID visualId=visuals.remove(fighterId);
        if(visualId!=null){Entity e=Bukkit.getEntity(visualId);if(e!=null)e.remove();}
        Entity fighter=Bukkit.getEntity(fighterId);
        if(fighter instanceof LivingEntity living && fighter.isValid())living.setInvisible(false);
    }

    private void reset(){running=false;removeUnits();for(var q:queues.values())q.clear();for(Side side:Side.values()){hp.put(side,maxHp.getOrDefault(side,1000));laneCursor.put(side,0);}updateBars();}
    @EventHandler public void onDeath(EntityDeathEvent event){Unit unit=units.remove(event.getEntity().getUniqueId());if(unit!=null){removeVisual(unit.id);event.getDrops().clear();event.setDroppedExp(0);}}
    @EventHandler public void onDamage(EntityDamageByEntityEvent event){
        Unit victim=units.get(event.getEntity().getUniqueId());
        Entity attacker=event.getDamager();if(attacker instanceof Projectile projectile&&projectile.getShooter() instanceof Entity shooter)attacker=shooter;
        Unit source=units.get(attacker.getUniqueId());
        // Battle units may only damage units of the opposite team.
        // Projectiles and external mobs must not interfere with arena units.
        if(victim!=null && (source==null || source.side==victim.side))event.setCancelled(true);
        if(source!=null && (victim==null || source.side==victim.side))event.setCancelled(true);
        else if(source!=null && !event.isCancelled())visualAttacks.put(source.id,tick);
    }
    @EventHandler public void onCombust(EntityCombustEvent event){if(units.containsKey(event.getEntity().getUniqueId()))event.setCancelled(true);}
    @EventHandler public void onChangeBlock(EntityChangeBlockEvent event){if(units.containsKey(event.getEntity().getUniqueId()))event.setCancelled(true);}
    @EventHandler public void onPrime(org.bukkit.event.entity.ExplosionPrimeEvent event){if(units.containsKey(event.getEntity().getUniqueId()))event.setCancelled(true);}
    @EventHandler public void onExplosion(EntityExplodeEvent event){if(running && isArenaLocation(event.getLocation()))event.blockList().clear();}
    @EventHandler public void onBlockExplosion(org.bukkit.event.block.BlockExplodeEvent event){if(running && isArenaLocation(event.getBlock().getLocation()))event.blockList().clear();}
    private boolean isArenaLocation(Location loc){
        if(loc==null||loc.getWorld()==null)return false;
        for(Side side:Side.values()){
            Location a=spawns.get(side),b=cores.get(side);
            if(a==null||b==null||a.getWorld()!=loc.getWorld()||b.getWorld()!=loc.getWorld())continue;
            double margin=16;
            if(loc.getX()>=Math.min(a.getX(),b.getX())-margin&&loc.getX()<=Math.max(a.getX(),b.getX())+margin&&loc.getZ()>=Math.min(a.getZ(),b.getZ())-margin&&loc.getZ()<=Math.max(a.getZ(),b.getZ())+margin)return true;
        }
        return false;
    }
    @EventHandler public void onBlockBreak(BlockBreakEvent event){if(isCore(event.getBlock()))event.setCancelled(true);}
    @EventHandler public void onBlockPlace(BlockPlaceEvent event){if(isCore(event.getBlock()))event.setCancelled(true);}
    private boolean isCore(Block block){for(Location loc:cores.values())if(loc!=null&&loc.getWorld()==block.getWorld()&&loc.getBlockX()==block.getX()&&loc.getBlockY()==block.getY()&&loc.getBlockZ()==block.getZ())return true;return false;}
}
