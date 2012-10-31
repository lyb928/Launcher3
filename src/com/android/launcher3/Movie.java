package com.android.launcher3;

import java.util.ArrayList;
import java.util.List;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.util.AndroidRuntimeException;

/**
 * 	A helper class of ValueAnimator, you can treat this class as a AnimatorSet,
 * the difference between Movie and AnimatorSet is that the Movie support for more flexible
 * arrangement of ValueAnimator, especially circulatable animation. 
 * @author yangbin.li
 */
public class Movie {

	private static final long DEFAULT_LEADER_DURATION = 1000;
//	private static final String TAG = "movie";
	
	private List<Actor> mActors;
	private MovieListener mListener;
	private Actor mCurrentActor;
	private Actor mFirstActor;
	
	private boolean mWaitForPrepared;
	private boolean mRunning;
	private long mDuration;
	private int mRepeatTime;
	
	public Movie() {
		this(DEFAULT_LEADER_DURATION);
	}
	
	public Movie(long duration) {
		mDuration = duration;
	}
	
	private void initEveryActor() {
		final int count = mActors.size();
		AnimatorListener partnerlistener = new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				if (mListener != null) {
					mListener.onOneActorOver((ValueAnimator) animation, mRepeatTime);
				}
			}
		};
		for (int i = 0; i < count; i++) {
			Actor actor = mActors.get(i);
			ValueAnimator role = actor.role;
			long duration = (long) (actor.weight * mDuration);
			role.setDuration(duration);
			AnimatorListener listener = new AnimatorListenerAdapter() {
				
				@Override
				public void onAnimationStart(Animator animation) {
					if (mFirstActor.equals(animation)) {
						mRepeatTime++;
						if (mListener != null) {
							mListener.onMovieAgain(mRepeatTime);
						}
					}
				}
				@Override
				public void onAnimationEnd(Animator animation) {
					if (mRunning) {
						Actor pastOne = mCurrentActor;
						mCurrentActor = pastOne.next;
						mCurrentActor.start();
					} else {
						return;
					}
					if (mListener != null) {
						mListener.onOneActorOver((ValueAnimator) animation, mRepeatTime);
					}
				}
			};
			role.removeListener(listener);
			role.addListener(listener);
			final ArrayList<ValueAnimator> partners = actor.partners;
			if (partners == null || partners.size() == 0) {
				continue;
			} else {
				for (int j = 0; j < partners.size(); j++) {
					ValueAnimator partner = partners.get(j);
					long durationPartner = 0;
					final Actor first = mFirstActor;
					final ArrayList<ValueAnimator> firstPartners = first.partners;
					if (firstPartners != null && firstPartners.contains(partner)) {
						durationPartner += first.role.getDuration();
					}
					Actor current = first.next;
					while (current != first) {
						final ArrayList<ValueAnimator> currentPartners = current.partners;
						if (currentPartners != null && currentPartners.contains(partner)) {
							durationPartner += current.role.getDuration();
						}
						current = current.next;
					}
					partner.setDuration(durationPartner);
					partner.removeListener(partnerlistener);
					partner.addListener(partnerlistener);
				}
			}
		}
	}
	
	private Movie addActor(ValueAnimator actor, float weight) {
		if (actor == null || isRunning() || weight < 0) {
			return this;
		}
		if (mActors == null) {
			mActors = new ArrayList<Actor>(5);
		}
		if (mFirstActor == null || mFirstActor.previous == null) {
			mCurrentActor = null;
		} else {
			mCurrentActor = mFirstActor.previous;
		}
		mCurrentActor = new Actor(actor, weight, mCurrentActor);
		mWaitForPrepared = mActors.add(mCurrentActor);
		return this;
	}
	
	public final Movie play(ValueAnimator actor, float weight) {
		return addActor(actor, weight);
	}
	
	public final Movie before(ValueAnimator actor, float weight) {
		if (mCurrentActor == null || mActors == null) {
			throw new AndroidRuntimeException("before what?");
		}
		return addActor(actor, weight);
	}
	
	public final Movie after(ValueAnimator actor, float weight) {
		if (mCurrentActor == null || mActors == null) {
			throw new AndroidRuntimeException("after what?");
		}
		addActor(actor, weight);
		mFirstActor = mCurrentActor;
		return this;
	}
	
	public final Movie withAll(ValueAnimator actor) {
		if (mCurrentActor == null || mActors == null || mFirstActor == null) {
			throw new AndroidRuntimeException("with what?");
		}
		final Actor first = mFirstActor;
		first.addPartner(actor);
		Actor current = first.next;
		while (current != first) {
			current.addPartner(actor);
			current = current.next;
		}
		return this;
	}
	
	public final Movie with(ValueAnimator actor, ValueAnimator specified) {
		if (mCurrentActor == null || mActors == null || mFirstActor == null) {
			throw new AndroidRuntimeException("with what?");
		} else if (specified == null || actor == null) {
			throw new AndroidRuntimeException("the with one can not be null");
		}
		final Actor first = mFirstActor;
		if (first.role == specified) {
			first.addPartner(actor);
			return this;
		}
		Actor current = first.next;
		while (current != first) {
			if (current.role == specified) {
				current.addPartner(actor);
				return this;
			} else {
				current = current.next;
			}
		}
		return this;
	}
	
	public final Movie with(ValueAnimator actor) {
		return with(actor, mCurrentActor.role);
	}
	
	public final void removeActor(ValueAnimator actor) {
		if (actor == null || isRunning() || mActors == null) {
			return;
		}
		mWaitForPrepared = mActors.remove(actor);
		//TODO: remove logic of linked list should be implemented here
	}
	
	/**
	 * Begin the movie 
	 */
	public final void action() {
		if (mActors != null) {
			start();
		} else {
			throw new AndroidRuntimeException("Can not start without actor " +
					",you must add Actor before action");
		}
	}
	
	private void start() {
		mCurrentActor = mFirstActor;
		if (mWaitForPrepared) {
			int count = mActors.size();
			float totalWeight = 0.0f;
			for (int i = 0; i < count; i++) {
				totalWeight += mActors.get(i).weight;
			}
			if (totalWeight != 1.0f) {
				if (totalWeight <= 0.0f) {
					throw new IllegalArgumentException("weight specified by actor wrong,it must be positive figure");
				} else {
					for (int i = 0; i < count; i++) {
						mActors.get(i).weight /= totalWeight;
					}
				}
			}
			initEveryActor();
			mWaitForPrepared = false;
		}
		mRunning = true;
		mRepeatTime = 0;
		if (mListener != null) {
			mListener.onMovieBegin();
		}
		mCurrentActor.start();
	}

	public void setMovieListener(MovieListener listener) {
		this.mListener = listener;
	}
	
	/**
	 * Stop the movie 
	 */
	public final void cut() {
	    if (mRunning && mListener != null) {
            mListener.onMovieOver();
        }
		mRunning = false;
		for (int i = 0; i < mActors.size(); i++) {
			Actor actor = mActors.get(i);
			if (actor != null) {
				actor.stop();
			}
		}
	}
	
	public final void reset() {
		if (mActors == null) {
			return;
		} else {
			mActors.clear();
			mFirstActor = null;
			mCurrentActor = null;
		}
	}
	
	public boolean isRunning() {
		return mRunning;
	}
	
	/**
	 * the smallest unit controlled by movie,
	 * every actor and its partners play start together in a movie
	 */
	class Actor {
		
		ValueAnimator role;
		float weight;
		Actor next;
		Actor previous;
		ArrayList<ValueAnimator> partners;
		
		Actor(ValueAnimator actor, float weight, Actor last) {
			this.role = actor;
			this.weight = weight;
			if (last != null) {
				Actor nextBefore = last.next;
				last.next = this;
				this.previous = last;
				this.next = nextBefore;
				nextBefore.previous = this;
			} else {
				if (mFirstActor != null) {
					throw new AndroidRuntimeException("One Movie can only have one linked actor list," +
							"must specify a next or previous node which belongs to the existed list");
				} else {
					mFirstActor = this;
				}
				this.previous = this;
				this.next = this;
			}
		}
		
		boolean addPartner(ValueAnimator actor) {
			if (actor == null) {
				return false;
			}
			if (partners == null || !partners.contains(actor)) {
				if (partners == null) {
					partners = new ArrayList<ValueAnimator>(5);
				}
				return partners.add(actor);
			} else {
				return false;
			}
		}
		
		void start() {
			if (role != null) {
				role.start();
			} else {
				throw new IllegalStateException("Actor start incorrectly");
			}
			if (partners != null) {
				for (int i = 0; i < partners.size(); i++) {
					ValueAnimator partner = partners.get(i);
					if(partner != null && !partner.isRunning() && !partner.isStarted()) {
						partner.start();
					}
				}
			}
		}
		
		void stop() {
			if (role != null) {
				role.cancel();
			} else {
				throw new IllegalStateException("Actor stop incorrectly");
			}
			if (partners != null) {
				for (int j = 0; j < partners.size(); j++) {
					ValueAnimator partner = partners.get(j);
					if (partner != null) {
						partner.cancel();
					}
				}
			}
		}
		
		@Override
		public boolean equals(Object o) {
			if (o instanceof ValueAnimator) {
				return o == this.role;
			}
			return super.equals(o);
		}

		@Override
		public String toString() {
			return role + ": previous is " + previous + ", next is " + next
					 + ", and has " + partners.size() + " partners ";
		}
	}
	
	/**
	 * A listener interface listen to the movie playing process
	 * @author yangbin.li
	 */
	public static interface MovieListener {
		/**
		 * Called when this movie begins
		 */
		void onMovieBegin();
		/**
		 * Movie will be run again and again unless you cut it, this hook will be called every
		 * time when it start repeat
		 * @param repeattime The repeattime of this movie, start from 1
		 */
		void onMovieAgain(int repeattime);
		/**
		 * Notify this movie has been cut, always followed cut()
		 */
		void onMovieOver();
		/**
		 * A movie is composed with many ValueAnimator as so-called Actor, Once an actor finish
		 * its show time, this hook will be invoked
		 * @param actor which actor is over
		 * @param repeat the repeattime of this actor, start from 1
		 */
		void onOneActorOver(ValueAnimator actor, int repeat);
	}
	
	/**
	 * A utility adapter class for interface MovieListener
	 * @author yangbin.li
	 */
	public static class MovieListenerAdapter implements MovieListener {

		@Override
		public void onMovieBegin() {
		}
		@Override
		public void onMovieAgain(int repeattime) {
		}
		@Override
		public void onMovieOver() {
		}
		@Override
		public void onOneActorOver(ValueAnimator actor, int repeat) {
		}
		
	}
}
