package com.neirofm.broadcast
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.neirofm.data.ProgramCatalog
import com.neirofm.data.RadioScript
import com.neirofm.data.ScriptLibrary
import com.neirofm.voice.VoiceEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class BroadcastScheduler(private val voice:VoiceEngine,private val context:Context, private val say:(String,String,()->Unit)->Unit = { text,presenter,done -> voice.speak(text,presenter,done) }) {
 private val handler=Handler(Looper.getMainLooper())
 @Volatile private var active=false
 private var lastProgram=""
 private val hourly=object:Runnable{override fun run(){if(!active)return;val now=System.currentTimeMillis();val next=((now/3_600_000L)+1)*3_600_000L;handler.postDelayed(this,(next-now).coerceAtLeast(1000));val h=SimpleDateFormat("H",Locale("ru")).format(Date(now));val custom=ScriptLibrary.get(context).filter{it.type==RadioScript.Type.TIME}.randomOrNull();if(custom!=null)say(custom.text,custom.presenter,{})else say("С вами NeiroFM. Сейчас ровно $h часов. Хорошего прослушивания.","maria",{})}}
 private val links=object:Runnable{override fun run(){if(!active)return;val program=ProgramCatalog.current();if(program.name!=lastProgram){lastProgram=program.name;say("Вы слушаете программу «${program.name}». ${program.description}. У микрофона ${presenterName(program.presenter)}.",program.presenter,{})}else{val scripts=listOf("Мы продолжаем эфир NeiroFM. Оставайтесь с нами — впереди ещё много музыки и интересных историй.","Пусть этот момент станет немного лучше с хорошей музыкой. Вы на NeiroFM.","Спасибо, что проводите это время с нами. Мы уже готовим для вас следующую историю.");say(scripts.random(),program.presenter,{})};handler.postDelayed(this,Random.nextLong(7,12)*60_000L)}}
 private val ads=object:Runnable{override fun run(){if(!active)return;val script=ScriptLibrary.get(context).filter{it.type==RadioScript.Type.ADVERTISEMENT}.randomOrNull();if(script!=null)say("Рекламная пауза. ${script.text}",script.presenter,{});handler.postDelayed(this,Random.nextLong(28,42)*60_000L)}}
 private val weather=object:Runnable{override fun run(){if(!active)return;val script=ScriptLibrary.get(context).filter{it.type==RadioScript.Type.WEATHER}.randomOrNull();if(script!=null)say(script.text,script.presenter,{});handler.postDelayed(this,Random.nextLong(150,210)*60_000L)}}
 private val podcast=object:Runnable{override fun run(){if(!active)return;val program=ProgramCatalog.current();val imported=ScriptLibrary.get(context).filter{it.type==RadioScript.Type.PODCAST|| (program.startHour==6&&it.type==RadioScript.Type.MORNING)||(program.startHour==22&&it.type==RadioScript.Type.NIGHT)};val script=imported.randomOrNull();if(script!=null)say(script.text,script.presenter,{})else say(ProgramCatalog.createPodcast(program),program.presenter,{});handler.postDelayed(this,Random.nextLong(24,36)*60_000L)}}
 fun start(){if(active)return;active=true;val now=System.currentTimeMillis();handler.postDelayed(hourly,(3_600_000L-now%3_600_000L).coerceAtLeast(1000));handler.postDelayed(links,2500);handler.postDelayed(podcast,Random.nextLong(60,150)*1000L);handler.postDelayed(ads,Random.nextLong(20,35)*60_000L);handler.postDelayed(weather,Random.nextLong(30,60)*60_000L)}
 fun announce(text:String,presenter:String,onComplete:(()->Unit)?=null){say(text,presenter){onComplete?.invoke()}}
 fun testVoice(){say("Проверка голоса NeiroFM. Вы слышите одного из ведущих нашей радиостанции.","maria",{})}
 fun stop(){active=false;handler.removeCallbacks(hourly);handler.removeCallbacks(links);handler.removeCallbacks(podcast);handler.removeCallbacks(ads);handler.removeCallbacks(weather)}
 private fun presenterName(id:String)=when(id){"maria"->"Мария";"max"->"Макс";else->"Алекс"}
}
