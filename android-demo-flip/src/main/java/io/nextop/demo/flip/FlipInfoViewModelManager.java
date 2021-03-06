package io.nextop.demo.flip;

import android.renderscript.Sampler;
import io.nextop.Id;
import io.nextop.Message;
import io.nextop.Nextop;
import io.nextop.WireValue;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func2;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class FlipInfoViewModelManager extends ThinViewModelManager<FlipInfoViewModel> {


    FlipInfoViewModelManager(Nextop nextop) {
        super(nextop);
    }


    // CONTROLLER

    void setIntro(final Id flipId, final String intro) {
        Map<WireValue, WireValue> content = Collections.singletonMap(
                WireValue.of("intro"),
                WireValue.of(intro));

        Message update = Message.newBuilder()
                .setRoute("POST http://" + Flip.REMOTE + "/flip/$flip-id/info")
                .set("flip-id", flipId)
                .setContent(content)
                .build();
        nextop.send(update);


        update(flipId, new Func2<FlipInfoViewModel, RxState, FlipInfoViewModel>() {
            @Override
            public FlipInfoViewModel call(FlipInfoViewModel flipInfoVm, RxState rxState) {
                flipInfoVm.set(intro, -1L);
                return flipInfoVm;
            }
        });
    }




    private void add(final Id flipId, Message message) {
        final List<WireValue> values = message.getContent().asList();
        if (!values.isEmpty()) {
            update(flipId, new Func2<FlipInfoViewModel, RxState, FlipInfoViewModel>() {
                @Override
                public FlipInfoViewModel call(FlipInfoViewModel flipInfoVm, RxState rxState) {
                    for (WireValue value : values) {
                        Map<WireValue, WireValue> m = value.asMap();

                        String intro = m.get(WireValue.of("intro")).asString();
                        long updateIndex = m.get(WireValue.of("most_recent_update_index")).asLong();

                        flipInfoVm.set(intro, updateIndex);
                    }
                    return flipInfoVm;
                    }
            });
        }
    }


    // VMM


    @Override
    protected void startUpdates(final FlipInfoViewModel flipInfoVm, final RxState state) {
        // sync info
        // then subscribe to updates


        Message sync = Message.newBuilder()
                .setRoute("GET http://" + Flip.REMOTE + "/flip/$flip-id/info")
                .set("flip-id", flipInfoVm.id)
                .build();
        state.bind(nextop.send(sync))
                .doOnNext(new Action1<Message>() {
                    @Override
                    public void call(Message message) {
                        add(flipInfoVm.id, message);
                        state.sync();
                    }
                }).doOnCompleted(new Action0() {
                    @Override
                    public void call() {
                        // start the poller
                        Action0 poller = new Action0() {
                            @Override
                            public void call() {
                                Message poll = Message.newBuilder()
                                        .setRoute("GET http://" + Flip.REMOTE + "/flip/$flip-id/info")
                                        .set("flip-id", flipInfoVm.id)
                                        .set("after", flipInfoVm.getUpdateIndex())
                                        .build();
                                state.bind(nextop.send(poll))
                                        .doOnNext(new Action1<Message>() {
                                            @Override
                                            public void call(final Message message) {
                                                add(flipInfoVm.id, message);
                                            }
                                        }).subscribe();
                            }
                        };
                        state.bind(AndroidSchedulers.mainThread().createWorker().schedulePeriodically(poller,
                                pollTimeoutMs, pollTimeoutMs, TimeUnit.MILLISECONDS));
                    }
                }).subscribe();
    }


    @Override
    protected FlipInfoViewModel create(Id id) {
        return new FlipInfoViewModel(id);
    }
}
