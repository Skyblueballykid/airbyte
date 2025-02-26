import React, { useEffect, useState } from "react";
import styled from "styled-components";
import { FormattedMessage } from "react-intl";

import { H1 } from "components";

import { useConfig } from "config";
import SyncCompletedModal from "views/Feedback/SyncCompletedModal";
import { useOnboardingService } from "hooks/services/Onboarding/OnboardingService";
import Status from "core/statuses";
import useWorkspace from "hooks/services/useWorkspace";
import { useConnectionList, useGetConnection, useSyncConnection } from "hooks/services/useConnectionHook";

import UseCaseBlock from "./UseCaseBlock";
import HighlightedText from "./HighlightedText";
import ProgressBlock from "./ProgressBlock";
import VideoItem from "./VideoItem";

const Title = styled(H1)`
  margin: 21px 0;
`;

const Videos = styled.div`
  width: 425px;
  height: 205px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 20px 0 50px;
  background: url("/video-background.svg") no-repeat;
  padding: 0 27px;
`;

const FinalStep: React.FC = () => {
  const config = useConfig();
  const { sendFeedback } = useWorkspace();
  const { feedbackPassed, passFeedback, visibleUseCases, useCaseLinks, skipCase } = useOnboardingService();
  const { mutateAsync: syncConnection } = useSyncConnection();
  const { connections } = useConnectionList();
  const connection = useGetConnection(connections[0].connectionId, {
    refetchInterval: 2500,
  });
  const [isOpen, setIsOpen] = useState(false);

  useEffect(() => {
    if (connection.latestSyncJobStatus === Status.SUCCEEDED && !feedbackPassed) {
      setIsOpen(true);
    }
  }, [connection.latestSyncJobStatus, feedbackPassed]);

  const onSkipFeedback = () => {
    passFeedback();
    setIsOpen(false);
  };

  const onSendFeedback = (feedback: string) => {
    sendFeedback({
      feedback,
      source: connection.source,
      destination: connection.destination,
    });
    passFeedback();
    setIsOpen(false);
  };

  const onSync = () => syncConnection(connections[0]);

  return (
    <>
      <Videos>
        <VideoItem
          small
          description={<FormattedMessage id="onboarding.watchVideo" />}
          videoId="sKDviQrOAbU"
          img="/videoCover.png"
        />
        <VideoItem
          small
          description={<FormattedMessage id="onboarding.exploreDemo" />}
          link={config.ui.demoLink}
          img="/videoCover.png"
        />
      </Videos>
      {!feedbackPassed && <ProgressBlock connection={connection} onSync={onSync} />}

      <Title bold>
        <FormattedMessage
          id="onboarding.useCases"
          values={{
            name: (name: React.ReactNode[]) => <HighlightedText>{name}</HighlightedText>,
          }}
        />
      </Title>

      {visibleUseCases?.map((item, key) => (
        <UseCaseBlock key={item} count={key + 1} href={useCaseLinks[item]} onSkip={skipCase} id={item} />
      ))}

      {isOpen ? <SyncCompletedModal onClose={onSkipFeedback} onPassFeedback={onSendFeedback} /> : null}
    </>
  );
};

export default FinalStep;
