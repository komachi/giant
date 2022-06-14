import _ from "lodash";
import React, {
  FC,
  KeyboardEventHandler,
  useCallback,
  useEffect,
  useState,
} from "react";
import DownIcon from "react-icons/lib/md/arrow-downward";
import UpIcon from "react-icons/lib/md/arrow-upward";
import styles from "./FindInput.module.css";
import { HighlightForSearchNavigation } from './model';

type FindInputProps = {
  value: string;
  setValue: (v: string) => void;
  performFind: (query: string) => Promise<void>;
  jumpToNextFindHit: () => void;
  jumpToPreviousFindHit: () => void;
  // TODO: could be null?
  highlights: HighlightForSearchNavigation[];
  focusedFindHighlightIndex: number | null;
};

// The backend will only return 500 pages of hits.
// If we get that many then we need to inform the user that there could be missing values.
// In the future we can make a paging system for find search hits.
const MAX_HITS = 500;

export const FindInput: FC<FindInputProps> = ({
  value,
  setValue,
  jumpToNextFindHit,
  jumpToPreviousFindHit,
  performFind,
  highlights,
  focusedFindHighlightIndex,
}) => {
  const [showWarning, setShowWarning] = useState(false);

  const debouncedPerformSearch = useCallback(_.debounce(performFind, 300), [
    performFind,
  ]);

  const onKeyDown: KeyboardEventHandler = (event) => {
    if (event.key === "Enter") {
      if (event.shiftKey) {
        jumpToPreviousFindHit();
      } else {
        jumpToNextFindHit();
      }
    }
  };

  useEffect(() => {
    if (highlights.length >= MAX_HITS) {
      setShowWarning(true);
      setTimeout(() => setShowWarning(false), 5000);
    }
  }, [highlights]);

  return (
    <div className={styles.container}>
      <div className={styles.inputContainer}>
        <input
          id="find-search-input"
          autoComplete="off"
          value={value}
          placeholder="Search document..."
          onKeyDown={onKeyDown}
          onChange={(e) => {
            setValue(e.target.value);
            debouncedPerformSearch(e.target.value);
          }}
        />
        <div className={styles.count}>
          {(focusedFindHighlightIndex !== null) ? focusedFindHighlightIndex + 1 : " - " }/
          {highlights.length > 0
            ? highlights.length >= MAX_HITS
              ? ">" + MAX_HITS
              : highlights.length
            : " - "}
        </div>
      </div>
      <button onClick={jumpToPreviousFindHit}>
        <UpIcon />
      </button>
      <button onClick={jumpToNextFindHit}>
        <DownIcon />
      </button>
      <div
        data-visible={showWarning || null}
        className={styles.warningContainer}
      >
        <div className={styles.warningArrow} />
        <div className={styles.warning}>
          Over 500 pages match your search only the first 500 highlights will be
          shown
        </div>
      </div>
    </div>
  );
};
