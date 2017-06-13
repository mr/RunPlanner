package net.ma.ttrobinson.runplanner

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.app.NotificationCompat
import android.support.v4.content.LocalBroadcastManager
import android.util.Log
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.subjects.PublishSubject
import net.ma.ttrobinson.runplanner.TimerService.Companion.BROADCAST_REMAINING_EXTRA
import net.ma.ttrobinson.runplanner.TimerService.Companion.BROADCAST_REMAINING_FILTER
import net.ma.ttrobinson.runplanner.TimerService.Companion.SKIP_CONTROL_KEY
import net.ma.ttrobinson.runplanner.TimerService.Companion.WORKOUT_STEP_KEY
import java.util.concurrent.TimeUnit

fun controlIntent(context: Context, controlState: ControlState): Intent {
    val intent = Intent(context, TimerService::class.java)
    intent.putExtra(TimerService.CONTROL_CODE_KEY, controlState)
    return intent
}

fun startTimer(context: Context, workoutSteps: IndexedWorkoutSteps) {
    val intent = Intent(context, TimerService::class.java)
    intent.putExtra(WORKOUT_STEP_KEY, workoutSteps)
    context.startService(intent)
}

fun controlTimer(context: Context, controlState: ControlState) {
    val intent = controlIntent(context, controlState)
    context.startService(intent)
}

fun skipTimer(context: Context, skipControl: SkipControl) {
    val intent = Intent(context, TimerService::class.java)
    intent.putExtra(SKIP_CONTROL_KEY, skipControl)
    context.startService(intent)
}

fun Context.receiveBroadcast(intentFilter: IntentFilter): Observable<Intent> {
    return Observable.create { emitter ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                if (p1 != null) {
                    emitter.onNext(p1)
                }
            }
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, intentFilter)

        emitter.setCancellable {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
        }
    }
}

fun Context.receiveBecomingNoisy(): Observable<Intent> =
    receiveBroadcast(IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)).filter {
        it.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY
    }

fun receiveRemaining(context: Context): Observable<WorkoutRemainingState> =
    context.receiveBroadcast(IntentFilter(BROADCAST_REMAINING_FILTER)).map {
        it.getSerializableExtra(BROADCAST_REMAINING_EXTRA) as WorkoutRemainingState
    }

fun <L, R> observeEither(left: Observable<L>, right: Observable<R>): Observable<Either<L, R>> =
    Observable.merge(
        left.map { Left<L, R>(it) },
        right.map { Right<L, R>(it) }
    )

fun tick(s: WorkoutRemainingState, ta: TimerAction): Long =
    if (s.workoutStep.time == -1L || ta == TimerAction.STAY) {
        s.workoutStep.time
    } else {
        s.workoutStep.time - 1L
    }

fun doSkip(
    index: Int,
    skip: SkipControl,
    s: WorkoutRemainingState,
    workout: List<WorkoutStep>
): Pair<Int, WorkoutRemainingState> {
    return when (skip) {
        SkipControl.FORWARD -> if (index == workout.size - 1) Pair(index, s)
        else {
            Pair(index + 1, s.copy(
                workoutStepState = WorkoutStepState.READY,
                workoutStep = workout[index + 1]
            ))
        }
        SkipControl.BACK -> if (index == 0) {
            Pair(0, WorkoutRemainingState(
                workoutStepState = WorkoutStepState.READY,
                completionStatus = s.completionStatus,
                workoutStep = workout[0],
                controlState = s.controlState
            ))
        } else {
            Pair(index - 1, s.copy(
                workoutStepState = WorkoutStepState.READY,
                workoutStep = workout[index - 1]
            ))
        }
    }
}

fun doControlStep(
    index: Int,
    command: ControlState,
    ta: TimerAction,
    s: WorkoutRemainingState,
    workout: List<WorkoutStep>
): Pair<Int, WorkoutRemainingState> {
    val r = tick(s, ta)
    return when (r) {
        -1L -> if (index == workout.size - 1)
            Pair(index, s.copy(
                completionStatus = CompletionStatus.COMPLETED,
                workoutStep = s.workoutStep.copy(time = 0),
                controlState = command
            ))
        else
            Pair(index + 1, WorkoutRemainingState(
                workoutStepState = WorkoutStepState.READY,
                completionStatus = CompletionStatus.RUNNING,
                workoutStep = workout[index + 1],
                controlState = command
            ))
        else -> Pair(index, WorkoutRemainingState(
            workoutStepState = WorkoutStepState.TICKING,
            completionStatus = CompletionStatus.RUNNING,
            workoutStep = s.workoutStep.copy(time = r),
            controlState = command
        ))
    }
}

class TimerService : Service() {
    companion object {
        val TAG = "TimerService"
        val NOTIFICATION_ID = 1
        val CONTROL_CODE_KEY = "control"
        val WORKOUT_STEP_KEY = "workoutStep"
        val SKIP_CONTROL_KEY = "skipControl"
        val BROADCAST_REMAINING_EXTRA = "broadcastRemainingExtra"
        val BROADCAST_REMAINING_FILTER = "broadcastRemainingFilter"
        val MAIN_ACTIVITY_INTENT_ID = 0
        val PLAY_INTENT_ID = 1
        val PAUSE_INTENT_ID = 2
        val STOP_INTENT_ID = 3
        val UTTERANCE_ID = "TimerServiceUtteranceId"
    }

    val disposable = CompositeDisposable()
    val controlCommands: PublishSubject<ControlState> = PublishSubject.create()
    val workouts: PublishSubject<IndexedWorkoutSteps> = PublishSubject.create()
    val skips: PublishSubject<SkipControl> = PublishSubject.create()

    var shouldStop = false

    lateinit var mainActivityIntent: Intent
    lateinit var contentIntent: PendingIntent
    lateinit var playIntent: Intent
    lateinit var playPendingIntent: PendingIntent
    lateinit var pauseIntent: Intent
    lateinit var pausePendingIntent: PendingIntent
    lateinit var stopIntent: Intent
    lateinit var stopPendingIntent: PendingIntent
    lateinit var playAction: NotificationCompat.Action
    lateinit var pauseAction: NotificationCompat.Action
    lateinit var stopAction: NotificationCompat.Action

    lateinit var textToSpeech: TextToSpeech
    var textToSpeechStatus = TextToSpeech.ERROR
    lateinit var audioManager: AudioManager
    val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    fun setupIntents() {
        mainActivityIntent = Intent(this, MainActivity::class.java)
        contentIntent = PendingIntent.getActivity(
            this, MAIN_ACTIVITY_INTENT_ID, mainActivityIntent, 0
        )

        playIntent = controlIntent(this, ControlState.PLAY)
        playPendingIntent = PendingIntent.getService(this, PLAY_INTENT_ID, playIntent, 0)

        pauseIntent = controlIntent(this, ControlState.PAUSE)
        pausePendingIntent = PendingIntent.getService(this, PAUSE_INTENT_ID, pauseIntent, 0)

        stopIntent = controlIntent(this, ControlState.STOP)
        stopPendingIntent = PendingIntent.getService(this, STOP_INTENT_ID, stopIntent, 0)

        playAction = NotificationCompat.Action.Builder(
            R.drawable.ic_play_arrow_black_24dp, "Play", playPendingIntent
        ).build()
        pauseAction = NotificationCompat.Action.Builder(
            R.drawable.ic_pause_black_24dp, "Pause", pausePendingIntent
        ).build()
        stopAction = NotificationCompat.Action.Builder(
            R.drawable.ic_stop_black_24dp, "Stop", stopPendingIntent
        ).build()
    }

    fun sendRemaining(remaining: WorkoutRemainingState) {
        val intent = Intent(BROADCAST_REMAINING_FILTER)
        intent.putExtra(BROADCAST_REMAINING_EXTRA, remaining)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun startNotification(state: WorkoutRemainingState) {
        val notification = NotificationCompat.Builder(this)
            .setContentTitle("Run Planner")
            .setContentText("${state.workoutStep.time} seconds remaining")
            .setSmallIcon(R.drawable.ic_directions_run_black_24dp)
            .setContentIntent(contentIntent)
            .addAction(when (state.controlState) {
                ControlState.PLAY -> pauseAction
                else -> playAction
            })
            .addAction(stopAction)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    fun speak(
        text: CharSequence,
        queueMode: Int = TextToSpeech.QUEUE_ADD,
        params: Bundle? = null
    ): Int {
        if (textToSpeechStatus != TextToSpeech.ERROR) {
            val reqRes = if (speakSem == 0) audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) else AudioManager.AUDIOFOCUS_REQUEST_GRANTED

            if (reqRes == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                return textToSpeech.speak(text, queueMode, params, UTTERANCE_ID)
            }
        }

        return TextToSpeech.ERROR
    }

    val controlledTimer: Observable<Pair<ControlState, TimerAction>> =
        controlCommands.switchMap { command ->
            when (command) {
                ControlState.PLAY -> Observable.interval(1L, TimeUnit.SECONDS).map {
                    TimerAction.TICK
                }
                else -> Observable.just(TimerAction.STAY)
            }.map { Pair(command, it) }
        }

    var speakSem = 0

    override fun onCreate() {
        super.onCreate()
        setupIntents()
        textToSpeech = TextToSpeech(this) {
            textToSpeechStatus = it
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

            @Suppress("OverridingDeprecatedMember")
            override fun onError(p0: String?) {
                speakSem--
            }

            override fun onDone(p0: String?) {
                speakSem--
                if (speakSem <= 0) {
                    audioManager.abandonAudioFocus(audioFocusChangeListener)
                }
                if (shouldStop) {
                    stopSelf()
                }
            }

            override fun onError(message: String?, errorCode: Int) {
                Log.e(TAG, message)
                speakSem--
            }

            override fun onStart(p0: String?) {
                speakSem++
            }
        })

        workouts.switchMap { (index, workout) ->
            val startState = WorkoutRemainingState(
                workoutStepState = WorkoutStepState.READY,
                completionStatus = CompletionStatus.STARTED,
                workoutStep =  workout[0],
                controlState = ControlState.PAUSE
            )

            observeEither(controlledTimer, skips).scan(Pair(0, startState)) {
                (index, s): Pair<Int, WorkoutRemainingState>,
                controlOrSkip: Either<Pair<ControlState, TimerAction>, SkipControl>
            ->
                when (controlOrSkip) {
                    is Left -> {
                        val (control, ta) = controlOrSkip.left
                        doControlStep(index, control, ta, s, workout)
                    }
                    is Right -> {
                        val skip = controlOrSkip.right
                        doSkip(index, skip, s, workout)
                    }
                }
            }.map {
                Triple(index, it, workout)
            }.distinctUntilChanged()
        }.subscribe { (_, statePair, workout) ->
            val (currentWorkout, state) = statePair
            Log.d(TAG, state.toString())

            sendRemaining(state)
            speakTotal(currentWorkout, state, workout)
            if (state.workoutStepState == WorkoutStepState.READY) {
                speakState(currentWorkout, state, workout)
            }
            if (state.completionStatus == CompletionStatus.COMPLETED) {
                speak("Workout complete")
                shouldStop = true
            }
            if (state.controlState == ControlState.STOP) {
                stopSelf()
                return@subscribe
            }
            startNotification(state)
        }.addTo(disposable)
    }

    fun speakTotal(currentWorkout: Int, state: WorkoutRemainingState, workout: List<WorkoutStep>) {
        val totalTime = workout.asSequence().map { it.time }.fold(0L) { total, time ->
            total + time
        }
        val currentTime = workout.asSequence().map { it.time }.foldIndexed(0L) { i, total, time ->
            if (i == currentWorkout) {
                total + (workout[currentWorkout].time - state.workoutStep.time)
            } else if (i < currentWorkout) {
                total + time
            } else {
                total
            }
        }

        if (totalTime / 2 == currentTime) {
            speak("You are halfway")
        }
    }

    fun speakState(currentWorkout: Int, state: WorkoutRemainingState, workout: List<WorkoutStep>) {
        val minutes = state.workoutStep.time / 60
        val seconds = state.workoutStep.time % 60
        speak("${formatTypeText(state.workoutStep.type)} for ${formatTimeText(minutes, seconds)}")

        if (state.workoutStep.type == WorkoutType.RUN) {
            val totalRuns = workout.fold(0) { num, (type) ->
                when (type) {
                    WorkoutType.RUN -> num + 1
                    WorkoutType.WALK -> num
                }
            }
            val currentRun = workout.foldIndexed(0) { i, current, (type) ->
                if (i <= currentWorkout && type == WorkoutType.RUN) {
                    current + 1
                } else {
                    current
                }
            }
            speak("You are on run $currentRun of $totalRuns")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val workoutSteps = intent?.getSerializableExtra(WORKOUT_STEP_KEY) as IndexedWorkoutSteps?
        val controlCode = intent?.getSerializableExtra(CONTROL_CODE_KEY) as ControlState?
        val skip = intent?.getSerializableExtra(SKIP_CONTROL_KEY) as SkipControl?

        if (workoutSteps != null) {
            workouts.onNext(workoutSteps)
        }

        if (controlCode != null) {
            controlCommands.onNext(controlCode)
        }

        if (skip != null) {
            skips.onNext(skip)
        }

        return START_STICKY
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        disposable.clear()
        textToSpeech.shutdown()
    }
}