package com.dasp.worldcup2026;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class Fixture {
    final long id;
    final long timestampSeconds;
    final String sourceDate;
    final String sourceTime;
    final String competition;
    final String round;
    final String venue;
    final String city;
    final String homeName;
    final String awayName;
    final int homeGoals;
    final int awayGoals;
    final String statusShort;
    final String statusLong;
    final int elapsed;
    final List<String> homeScorers;
    final List<String> awayScorers;

    Fixture(long id,long timestampSeconds,String competition,String round,String venue,String city,String homeName,String awayName,int homeGoals,int awayGoals,String statusShort,String statusLong,int elapsed,List<String> homeScorers,List<String> awayScorers) {
        this(id,timestampSeconds,"","",competition,round,venue,city,homeName,awayName,homeGoals,awayGoals,statusShort,statusLong,elapsed,homeScorers,awayScorers);
    }

    Fixture(long id,long timestampSeconds,String sourceDate,String sourceTime,String competition,String round,String venue,String city,String homeName,String awayName,int homeGoals,int awayGoals,String statusShort,String statusLong,int elapsed,List<String> homeScorers,List<String> awayScorers) {
        this.id=id; this.timestampSeconds=timestampSeconds; this.sourceDate=clean(sourceDate); this.sourceTime=clean(sourceTime);
        this.competition=clean(competition); this.round=clean(round); this.venue=clean(venue); this.city=clean(city);
        this.homeName=clean(homeName); this.awayName=clean(awayName); this.homeGoals=homeGoals; this.awayGoals=awayGoals;
        this.statusShort=clean(statusShort); this.statusLong=clean(statusLong); this.elapsed=elapsed;
        this.homeScorers=copyList(homeScorers); this.awayScorers=copyList(awayScorers);
    }

    static Fixture fromOpenFootballJson(JSONObject item,String sourceCompetition) {
        int homeGoals=-1, awayGoals=-1;
        Object scoreValue=item.opt("score");
        if(scoreValue instanceof JSONObject){ JSONArray ft=((JSONObject)scoreValue).optJSONArray("ft"); if(ft!=null&&ft.length()>=2){homeGoals=ft.optInt(0,-1);awayGoals=ft.optInt(1,-1);} }
        else if(scoreValue instanceof JSONArray){JSONArray score=(JSONArray)scoreValue;if(score.length()>=2){homeGoals=score.optInt(0,-1);awayGoals=score.optInt(1,-1);}}
        boolean finished=homeGoals>=0&&awayGoals>=0;
        String competition=item.optString("competition",""); if(competition.trim().isEmpty()) competition=sourceCompetition;
        String sourceDate=item.optString("date",""); String sourceTime=item.optString("time","");
        return new Fixture(item.optLong("num",stableId(item)),parseTimestamp(sourceDate,sourceTime),sourceDate,sourceTime,competition,item.optString("round"),item.optString("ground"),item.optString("city"),item.optString("team1"),item.optString("team2"),homeGoals,awayGoals,finished?"FT":"NS",finished?"Match Finished":"Not Started",0,readScorers(item,"goals1","scorers1"),readScorers(item,"goals2","scorers2"));
    }

    private static List<String> readScorers(JSONObject item,String primaryKey,String fallbackKey){
        List<String> result=new ArrayList<>(); JSONArray array=item.optJSONArray(primaryKey); if(array==null) array=item.optJSONArray(fallbackKey); if(array==null)return result;
        for(int i=0;i<array.length();i++){Object value=array.opt(i);if(value instanceof JSONObject){JSONObject goal=(JSONObject)value;String name=goal.optString("name","").trim();String minute=goal.optString("minute","").trim();if(!name.isEmpty())result.add(minute.isEmpty()?name:name+" "+minute+"'");}else{String text=array.optString(i,"").trim();if(!text.isEmpty())result.add(text);}}
        return result;
    }

    boolean hasScorers(){ return !homeScorers.isEmpty() || !awayScorers.isEmpty(); }
    String matchTitle(){return (homeName.isEmpty()?"TBD":homeName)+" vs "+(awayName.isEmpty()?"TBD":awayName);}
    String scoreTitle(){return homeGoals>=0&&awayGoals>=0?homeName+" "+homeGoals+" - "+awayGoals+" "+awayName:matchTitle();}
    String dateKey(){
        if(!sourceDate.isEmpty())return sourceDate;
        if(timestampSeconds<=0)return "";
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));return f.format(new Date(timestampSeconds*1000L));
    }
    String dateLine(){
        if(!sourceDate.isEmpty()&&!sourceTime.isEmpty()&&!hasExplicitTimezone(sourceTime))return sourceDate+" • "+sourceTime;
        if(timestampSeconds<=0)return "Time TBD";
        DateFormat f=new SimpleDateFormat("d MMM yyyy, h:mm a 'IST'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));return f.format(new Date(timestampSeconds*1000L));
    }
    String locationLine(){if(venue.isEmpty()&&city.isEmpty())return "";if(venue.isEmpty())return city;if(city.isEmpty())return venue;return venue+", "+city;}
    String statusLine(){if(isLive())return "Live"+(elapsed>0?" - "+elapsed+"'":"");if(isFinished())return statusLong.isEmpty()?"Finished":statusLong;return dateLine();}
    boolean isLive(){return "1H".equals(statusShort)||"HT".equals(statusShort)||"2H".equals(statusShort)||"ET".equals(statusShort)||"BT".equals(statusShort)||"P".equals(statusShort)||"LIVE".equals(statusShort)||"INT".equals(statusShort);}
    boolean isFinished(){return "FT".equals(statusShort)||"AET".equals(statusShort)||"PEN".equals(statusShort);}
    boolean isUpcoming(){return !isLive()&&!isFinished();}
    private static boolean hasExplicitTimezone(String time){String[] p=clean(time).split("\\s+");return p.length>=2&&(p[1].startsWith("UTC")||p[1].startsWith("GMT"));}
    private static List<String> copyList(List<String> source){return source==null?new ArrayList<String>():new ArrayList<>(source);}
    private static String clean(String value){return value==null||"null".equalsIgnoreCase(value)?"":value.trim();}
    private static long parseTimestamp(String date,String time){try{String[] p=clean(time).split("\\s+");String clock=p.length>0?p[0]:"00:00";String zone=p.length>1?p[1]:"UTC";SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US);f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone(toGmtZone(zone)));Date d=f.parse(clean(date)+" "+clock);return d==null?0:d.getTime()/1000L;}catch(Exception ignored){return 0;}}
    private static String toGmtZone(String zone){String z=clean(zone).replace("UTC","GMT");if("GMT".equals(z))return z;int plus=z.indexOf('+'),minus=z.indexOf('-'),idx;if(plus>=0&&minus>=0)idx=Math.min(plus,minus);else idx=plus>=0?plus:minus;if(idx<0)return "GMT";String o=z.substring(idx);if(!o.contains(":"))o+=":00";return "GMT"+o;}
    private static long stableId(JSONObject item){String seed=item.optString("date")+"|"+item.optString("time")+"|"+item.optString("team1")+"|"+item.optString("team2")+"|"+item.optString("round");return Math.abs((long)seed.hashCode());}
}
