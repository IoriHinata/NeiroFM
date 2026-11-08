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

class BroadcastScheduler(private val voice:VoiceEngine,private val context:Context) {
 private val handler=Handler(Looper.getMainLooper())
 @Volatile private var active=false
 private var presenterIndex=0
 private var lastProgram=""
 private val hourly=object:Runnable{override fun run(){if(!active)return;val now=System.currentTimeMillis();val next=((now/3_600_000L)+1)*3_600_000L;handler.postDelayed(this,(next-now).coerceAtLeast(1000));val h=SimpleDateFormat("H",Locale("ru")).format(Date(now));voice.speak("С вами NeiroFM. Сейчас ровно $h часов. Хорошего прослушивания.","maria")}}
 private val links=object:Runnable{override fun run(){if(!active)return;val program=ProgramCatalog.current();if(program.name!=lastProgram){lastProgram=program.name;voice.speak("Вы слушаете программу «${program.name}». ${program.description}. У микрофона ${presenterName(program.presenter)}.",program.presenter)}else{val scripts=listOf("Мы продолжаем эфир NeiroFM. Оставайтесь с нами — впереди ещё много музыки и интересных историй.","Пусть этот момент станет немного лучше с хорошей музыкой. Вы на NeiroFM.","Спасибо, что проводите это время с нами. Мы уже готовим для вас следующую историю.");voice.speak(scripts.random(),program.presenter)};handler.postDelayed(this,Random.nextLong(7,12)*60_000L)}}
 private val podcast=object:Runnable{override fun run(){if(!active)return;val program=ProgramCatalog.current();val imported=ScriptLibrary.get(context).filter{it.type==RadioScript.Type.PODCAST|| (program.startHour==6&&it.type==RadioScript.Type.MORNING)||(program.startHour==22&&it.type==RadioScript.Type.NIGHT)};val script=imported.randomOrNull();if(script!=null)voice.speak(script.text,script.presenter)else voice.speak(ProgramCatalog.createPodcast(program),program.presenter);handler.postDelayed(this,Random.nextLong(24,36)*60_000L)}}
 fun start(){if(active)return;active=true;val now=System.currentTimeMillis();handler.postDelayed(hourly,(3_600_000L-now%3_600_000L).coerceAtLeast(1000));handler.postDelayed(links,2500);handler.postDelayed(podcast,Random.nextLong(60,150)*1000L)}
 fun announce(text:String,presenter:String,onComplete:(()->Unit)?=null){voice.speak(text,presenter,onComplete)}
 fun testVoice(){voice.speak("Проверка голоса NeiroFM. Вы слышите одного из ведущих нашей радиостанции.","maria")}
 fun stop(){active=false;handler.removeCallbacks(hourly);handler.removeCallbacks(links);handler.removeCallbacks(podcast)}
 private fun presenterName(id:String)=when(id){"maria"->"Мария";"max"->"Макс";else->"Алекс"}
}
